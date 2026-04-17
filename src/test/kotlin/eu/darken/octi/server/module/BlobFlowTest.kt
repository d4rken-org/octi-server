package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.*

class BlobFlowTest : TestRunner() {

    private val testModuleId = "eu.darken.octi.module.test"

    @Serializable
    data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
        val offsetBytes: Long = 0,
        val expiresAt: String = "",
        val state: String = "",
    )

    @Serializable
    data class FinalizeInfo(
        val blobId: String = "",
        val sessionId: String = "",
        val sizeBytes: Long = 0,
        val state: String = "",
    )

    @Serializable
    data class BlobListInfo(
        val moduleEtag: String = "",
        val blobs: List<BlobEntry> = emptyList(),
    )

    @Serializable
    data class BlobEntry(
        val blobId: String = "",
        val sizeBytes: Long = 0,
        val hashAlgorithm: String? = null,
        val hashHex: String? = null,
    )

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun base64Encode(data: ByteArray): String {
        return Base64.getEncoder().encodeToString(data)
    }

    @Test
    fun `create upload session reserves quota`() = runTest2 {
        val creds = createDevice()
        // First write a module so it exists
        writeModule(creds, testModuleId, data = "init")

        http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 1024, "hashAlgorithm": "sha256", "hashHex": "${"a".repeat(64)}"}""")
        }.apply {
            status shouldBe HttpStatusCode.Created
            val session = body<SessionInfo>()
            session.blobId.shouldNotBeEmpty()
            session.sessionId.shouldNotBeEmpty()
            session.offsetBytes shouldBe 0
            session.state shouldBe "active"
        }
    }

    @Test
    fun `session HEAD returns offset and expiry`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 100}""")
        }.body<SessionInfo>()

        http.get("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers["Upload-Offset"] shouldBe "0"
            headers["Upload-Length"] shouldBe "100"
            headers["Upload-State"] shouldBe "active"
            headers["X-Blob-ID"] shouldNotBe null
        }
    }

    @Test
    fun `full upload and commit flow`() = runTest2 {
        val creds = createDevice()
        // Use PUT with If-None-Match to create the module — this gives us a reliable ETag
        val doc = "initial".toByteArray()
        val createResp = http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
        }
        createResp.status shouldBe HttpStatusCode.OK
        val initialEtag = createResp.headers["ETag"]!!.trim('"')

        // Create blob data
        val blobData = "hello-blob-content".toByteArray()
        val blobHash = sha256Hex(blobData)

        // 1. Create session
        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        session.state shouldBe "active"

        // 2. Upload data via PATCH
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }.apply {
            status shouldBe HttpStatusCode.NoContent
            headers["Upload-Offset"] shouldBe blobData.size.toString()
        }

        // 3. Finalize
        val finalized = http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
        }.body<FinalizeInfo>()

        finalized.state shouldBe "complete"
        finalized.sizeBytes shouldBe blobData.size.toLong()

        // 4. Commit via PUT
        val newDocument = "updated-root-doc".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", initialEtag)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(newDocument)}", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers["ETag"] shouldNotBe null
        }

        // 5. Read should return updated document
        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe "updated-root-doc"
        }

        // 6. Blob list should show the committed blob
        http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            val blobList = body<BlobListInfo>()
            blobList.blobs.size shouldBe 1
            blobList.blobs[0].blobId shouldBe session.blobId
            blobList.blobs[0].sizeBytes shouldBe blobData.size.toLong()
        }
    }

    @Test
    fun `PUT with If-None-Match star creates new module`() = runTest2 {
        val creds = createDevice()

        val doc = "new-module-doc".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers["ETag"] shouldNotBe null
        }

        readModule(creds, testModuleId) shouldBe "new-module-doc"
    }

    @Test
    fun `PUT with If-None-Match star fails if module exists`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "existing")

        val doc = "attempt".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
        }.apply {
            status shouldBe HttpStatusCode.PreconditionFailed
        }
    }

    @Test
    fun `PUT etag roundtrip works`() = runTest2 {
        val creds = createDevice()
        val doc1 = "v1".toByteArray()

        // Create via PUT
        val createResp = http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc1)}", "blobRefs": []}""")
        }
        createResp.status shouldBe HttpStatusCode.OK
        val etag1 = createResp.headers["ETag"]!!.trim('"')
        etag1.shouldNotBeEmpty()

        // Read and verify same ETag
        val readEtag = readModuleRaw(creds, testModuleId).headers["ETag"]!!.trim('"')
        readEtag shouldBe etag1

        // Update via PUT with matching ETag
        val doc2 = "v2".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", etag1)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc2)}", "blobRefs": []}""")
        }.apply {
            status shouldBe HttpStatusCode.OK
        }

        readModule(creds, testModuleId) shouldBe "v2"
    }

    @Test
    fun `PUT with stale ETag fails with 412`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "v1")

        val doc = "v2".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", "stale-etag")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
        }.apply {
            status shouldBe HttpStatusCode.PreconditionFailed
        }
    }

    @Test
    fun `PUT without If-Match or If-None-Match fails with 412`() = runTest2 {
        val creds = createDevice()

        val doc = "test".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
        }.apply {
            status shouldBe HttpStatusCode.PreconditionFailed
        }
    }

    @Test
    fun `checksum mismatch at finalize returns 422`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val blobData = "test-data".toByteArray()
        val wrongHash = "a".repeat(64)

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$wrongHash"}""")
        }.body<SessionInfo>()

        // Upload
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }

        // Finalize should fail with checksum mismatch
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.UnprocessableEntity
        }
    }

    @Test
    fun `abort session releases quota`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 1024}""")
        }.body<SessionInfo>()

        // Abort
        http.delete("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
        }

        // Session should no longer exist
        http.get("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun `blob list on absent module returns empty`() = runTest2 {
        val creds = createDevice()

        http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            val list = body<BlobListInfo>()
            list.blobs shouldBe emptyList()
        }
    }

    @Test
    fun `commit with duplicate blobId values returns 400`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")
        val etag = readModuleRaw(creds, testModuleId).headers["ETag"]!!.trim('"')

        val doc = "test".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", etag)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": [{"blobId": "same-id"}, {"blobId": "same-id"}]}""")
        }.apply {
            status shouldBe HttpStatusCode.BadRequest
        }
    }

    @Test
    fun `zero-length blob upload succeeds`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val emptyHash = sha256Hex(ByteArray(0))

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 0, "hashAlgorithm": "sha256", "hashHex": "$emptyHash"}""")
        }.apply {
            status shouldBe HttpStatusCode.Created
        }.body<SessionInfo>()

        // Finalize immediately (zero bytes to upload)
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun `resumable multi-chunk upload preserves all bytes`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")
        val etag = readModuleRaw(creds, testModuleId).headers["ETag"]!!.trim('"')

        val chunk1 = ByteArray(4096) { (it and 0xFF).toByte() }
        val chunk2 = ByteArray(4096) { ((it + 128) and 0xFF).toByte() }
        val fullBlob = chunk1 + chunk2
        val blobHash = sha256Hex(fullBlob)

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${fullBlob.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        // First chunk at offset 0
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(chunk1)
        }.apply {
            status shouldBe HttpStatusCode.NoContent
            headers["Upload-Offset"] shouldBe chunk1.size.toString()
        }

        // Second chunk at offset chunk1.size — must append, not truncate
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", chunk1.size.toString())
            contentType(ContentType.Application.OctetStream)
            setBody(chunk2)
        }.apply {
            status shouldBe HttpStatusCode.NoContent
            headers["Upload-Offset"] shouldBe fullBlob.size.toString()
        }

        // Finalize with hash of the full concatenation — passes only if first chunk survived
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
        }

        // Commit and verify the downloaded blob bytes match exactly
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", etag)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode("root".toByteArray())}", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }.status shouldBe HttpStatusCode.OK

        http.get("/v1/module/$testModuleId/blobs/${session.blobId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            readRawBytes() shouldBe fullBlob
        }
    }

    @Test
    fun `PATCH with offset less than current is rejected with 409`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val chunk = ByteArray(1024) { it.toByte() }
        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 4096}""")
        }.body<SessionInfo>()

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(chunk)
        }.status shouldBe HttpStatusCode.NoContent

        // Attempt to rewind — server is at offset 1024, client sends 0
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(chunk)
        }.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `PUT with weak If-Match returns 400`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val doc = "v2".toByteArray()
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            // weak etag — If-Match requires strong comparison per RFC 7232 §3.1
            header("If-Match", "W/\"abc123\"")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
        }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `cross-account device cannot read another account's blob`() = runTest2 {
        val alice = createDevice()
        val bob = createDevice()

        // Alice uploads and commits a blob
        val initDoc = "alice-init".toByteArray()
        val createResp = http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(initDoc)}", "blobRefs": []}""")
        }
        val initEtag = createResp.headers["ETag"]!!.trim('"')

        val blobData = "alice-secret".toByteArray()
        val blobHash = sha256Hex(blobData)
        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(alice)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(alice)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
            header("If-Match", initEtag)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode("root".toByteArray())}", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }.status shouldBe HttpStatusCode.OK

        // Bob (different account) tries to target Alice's device-id — must fail
        http.get("/v1/module/$testModuleId/blobs/${session.blobId}") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
        }.status shouldBe HttpStatusCode.NotFound

        http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
        }.apply {
            // Target device not in Bob's account
            status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun `cross-account device cannot probe another account's session`() = runTest2 {
        val alice = createDevice()
        val bob = createDevice()

        writeModule(alice, testModuleId, data = "init")
        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 1024}""")
        }.body<SessionInfo>()

        // Bob tries to read session status — must fail (scope mismatch via accountId)
        http.get("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(bob)
        }.status shouldBe HttpStatusCode.NotFound

        // Bob tries to abort Alice's session — must fail
        http.delete("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(bob)
        }.status shouldBe HttpStatusCode.NotFound

        // Bob tries to finalize Alice's session — must fail
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(bob)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `retried finalize is idempotent`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val blobData = "idem-test".toByteArray()
        val blobHash = sha256Hex(blobData)

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }

        // First finalize
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
        }

        // Second finalize — should be idempotent
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
            body<FinalizeInfo>().state shouldBe "complete"
        }
    }
}
