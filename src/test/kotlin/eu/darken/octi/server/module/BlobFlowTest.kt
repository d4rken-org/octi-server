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
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.readText

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

        http.head("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
    fun `upload session operations require target device id`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 1}""")
        }.body<SessionInfo>()

        http.head("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.BadRequest

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(1))
        }.status shouldBe HttpStatusCode.BadRequest

        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status shouldBe HttpStatusCode.BadRequest

        http.delete("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.BadRequest
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
        val blobHash = blobData.sha256Hex()

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
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }

        // Finalize should fail with checksum mismatch
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
        }

        // Session should no longer exist
        http.head("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
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

        val emptyHash = ByteArray(0).sha256Hex()

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
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
        val blobHash = fullBlob.sha256Hex()

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${fullBlob.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        // First chunk at offset 0
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(chunk)
        }.status shouldBe HttpStatusCode.NoContent

        // Attempt to rewind — server is at offset 1024, client sends 0
        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
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
        val blobHash = blobData.sha256Hex()
        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
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
        http.head("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
        }.status shouldBe HttpStatusCode.NotFound

        // Bob tries to abort Alice's session — must fail
        http.delete("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
        }.status shouldBe HttpStatusCode.NotFound

        // Bob tries to finalize Alice's session — must fail
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status shouldBe HttpStatusCode.NotFound
    }

    @Serializable
    private data class DeleteBlobInfo(val etag: String = "")

    @Serializable
    private data class StorageInfo(
        val usedBytes: Long = 0,
        val reservedBytes: Long = 0,
    )

    private suspend fun TestEnvironment.commitBlob(
        creds: Credentials,
        moduleId: String,
        blobData: ByteArray,
        ifMatch: String,
        rootDoc: ByteArray = "root".toByteArray(),
    ): Pair<String, String> {
        val blobHash = blobData.sha256Hex()
        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()
        http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }
        http.post("/v1/module/$moduleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        // PUT commit is authoritative for blobRefs — preserve existing refs so sequential commits accumulate.
        val existingIds = http.get("/v1/module/$moduleId/blobs") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.body<BlobListInfo>().blobs.map { it.blobId }
        val allIds = existingIds + session.blobId
        val refsJson = allIds.joinToString(",") { """{"blobId": "$it"}""" }
        val commitEtag = http.put("/v1/module/$moduleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", ifMatch)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode(rootDoc)}", "blobRefs": [$refsJson]}""")
        }.headers["ETag"]!!.trim('"')
        return session.blobId to commitEtag
    }

    private suspend fun TestEnvironment.createModule(creds: Credentials, moduleId: String): String {
        return http.put("/v1/module/$moduleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode("init".toByteArray())}", "blobRefs": []}""")
        }.headers["ETag"]!!.trim('"')
    }

    private suspend fun TestEnvironment.getUsedBytes(creds: Credentials): Long =
        http.get("/v1/account/storage") { addCredentials(creds) }.body<StorageInfo>().usedBytes

    @Test
    fun `delete blob drops quota and advances etag and cleans disk`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val blobData = "hello-blob-delete".toByteArray()
        val (blobId, moduleEtag2) = commitBlob(creds, testModuleId, blobData, moduleEtag1)

        // Locate the on-disk blob directory so we can verify async cleanup.
        val moduleDir = getModulesPath(creds).resolve(testModuleId.toModuleDirName())
        val storageKey = Regex(""""storageKey"\s*:\s*"([^"]+)"""")
            .find(moduleDir.resolve("module.json").readText())!!
            .groupValues[1]
        val blobDir = moduleDir.resolve("blobs").resolve(storageKey.take(4)).resolve(storageKey)
        blobDir.exists() shouldBe true

        val usedBefore = getUsedBytes(creds)

        val deleteResp = http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", moduleEtag2)
        }
        deleteResp.status shouldBe HttpStatusCode.OK
        val newEtag = deleteResp.body<DeleteBlobInfo>().etag
        newEtag.shouldNotBeEmpty()
        newEtag shouldNotBe moduleEtag2
        deleteResp.headers["ETag"]?.trim('"') shouldBe newEtag

        getUsedBytes(creds) shouldBe (usedBefore - blobData.size.toLong())

        http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            val list = body<BlobListInfo>()
            list.moduleEtag shouldBe newEtag
            list.blobs shouldBe emptyList()
        }

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.NotFound

        // Async cleanup runs on AppScope(Dispatchers.IO); poll briefly for the dir to vanish.
        var gone = false
        repeat(30) {
            if (!blobDir.exists()) { gone = true; return@repeat }
            Thread.sleep(100)
        }
        gone shouldBe true
    }

    @Test
    fun `delete blob retry returns 404 and does not double-subtract quota`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val (blobId, moduleEtag2) = commitBlob(creds, testModuleId, "retry-blob".toByteArray(), moduleEtag1)

        val firstResp = http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", moduleEtag2)
        }
        firstResp.status shouldBe HttpStatusCode.OK
        val afterFirst = getUsedBytes(creds)
        val newEtag = firstResp.body<DeleteBlobInfo>().etag

        http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", newEtag)
        }.status shouldBe HttpStatusCode.NotFound

        getUsedBytes(creds) shouldBe afterFirst
    }

    @Test
    fun `delete blob with stale If-Match returns 412 with current etag`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val (blobId, moduleEtag2) = commitBlob(creds, testModuleId, "stale-test".toByteArray(), moduleEtag1)

        val staleResp = http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", moduleEtag1)
        }
        staleResp.status shouldBe HttpStatusCode.PreconditionFailed
        // Server should hint the current etag so clients can retry without a separate GET.
        staleResp.headers["ETag"]?.trim('"') shouldBe moduleEtag2

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `delete blob from peer device in same account`() = runTest2 {
        val alice = createDevice()
        val bob = createDevice(alice) // bob joins alice's account via share code
        val moduleEtag1 = createModule(alice, testModuleId)
        val blobData = "peer-delete".toByteArray()
        val (blobId, moduleEtag2) = commitBlob(alice, testModuleId, blobData, moduleEtag1)

        val usedBefore = getUsedBytes(alice)

        // Bob targets Alice's device — same account, quota is shared.
        val resp = http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
            header("If-Match", moduleEtag2)
        }
        resp.status shouldBe HttpStatusCode.OK
        resp.body<DeleteBlobInfo>().etag shouldNotBe moduleEtag2

        getUsedBytes(alice) shouldBe (usedBefore - blobData.size.toLong())
        getUsedBytes(bob) shouldBe (usedBefore - blobData.size.toLong())

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
        }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `delete blob without If-Match returns 400`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val (blobId, _) = commitBlob(creds, testModuleId, "no-etag".toByteArray(), moduleEtag1)

        http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `delete blob with weak If-Match returns 400`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val (blobId, _) = commitBlob(creds, testModuleId, "weak-etag".toByteArray(), moduleEtag1)

        http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", "W/\"whatever\"")
        }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `delete blob with wildcard If-Match succeeds when blob exists`() = runTest2 {
        // RFC 7232 §3.1: `If-Match: *` means "if the resource currently exists" — the
        // standard delete-if-exists idiom. Pre-fix this returned 400; now it succeeds.
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val (blobId, _) = commitBlob(creds, testModuleId, "wildcard".toByteArray(), moduleEtag1)

        http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", "*")
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `delete blob unknown blobId returns 404`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val (_, moduleEtag2) = commitBlob(creds, testModuleId, "any".toByteArray(), moduleEtag1)

        http.delete("/v1/module/$testModuleId/blobs/${UUID.randomUUID()}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", moduleEtag2)
        }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `delete blob on unknown module returns 404`() = runTest2 {
        val creds = createDevice()

        http.delete("/v1/module/$testModuleId/blobs/${UUID.randomUUID()}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", "whatever")
        }.status shouldBe HttpStatusCode.NotFound
    }

    @Test
    fun `delete blob cross-account denied`() = runTest2 {
        val alice = createDevice()
        val bob = createDevice()
        val moduleEtag1 = createModule(alice, testModuleId)
        val (blobId, moduleEtag2) = commitBlob(alice, testModuleId, "alice-secret".toByteArray(), moduleEtag1)

        http.delete("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(bob)
            header("If-Match", moduleEtag2)
        }.status shouldBe HttpStatusCode.NotFound

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", alice.deviceId.toString()) }
            addCredentials(alice)
        }.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `delete blob keeps other blobs intact`() = runTest2 {
        val creds = createDevice()
        var etag = createModule(creds, testModuleId)
        val (idA, etagA) = commitBlob(creds, testModuleId, "AAA".toByteArray(), etag)
        etag = etagA
        val (idB, etagB) = commitBlob(creds, testModuleId, "BBBB".toByteArray(), etag)
        etag = etagB
        val (idC, etagC) = commitBlob(creds, testModuleId, "CCCCC".toByteArray(), etag)
        etag = etagC

        // Sanity: the third commit should keep A and B alongside C.
        http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.body<BlobListInfo>().blobs.map { it.blobId }.toSet() shouldBe setOf(idA, idB, idC)

        val deleteResp = http.delete("/v1/module/$testModuleId/blobs/$idB") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", etag)
        }
        deleteResp.status shouldBe HttpStatusCode.OK
        val etagAfter = deleteResp.body<DeleteBlobInfo>().etag

        http.get("/v1/module/$testModuleId/blobs") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            val list = body<BlobListInfo>()
            list.moduleEtag shouldBe etagAfter
            list.blobs.map { it.blobId }.toSet() shouldBe setOf(idA, idC)
        }

        http.get("/v1/module/$testModuleId/blobs/$idA") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.readRawBytes() shouldBe "AAA".toByteArray()

        http.get("/v1/module/$testModuleId/blobs/$idC") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.readRawBytes() shouldBe "CCCCC".toByteArray()
    }

    @Test
    fun `delete blob releases quota for immediate reuse`() = runTest2 {
        val creds = createDevice()
        val moduleEtag1 = createModule(creds, testModuleId)
        val blob1 = ByteArray(4096) { it.toByte() }
        val (blobId1, moduleEtag2) = commitBlob(creds, testModuleId, blob1, moduleEtag1)

        // Delete the blob and immediately reserve a new session of the same size —
        // only succeeds if the quota release happened synchronously inside the DELETE's lock.
        val deleteResp = http.delete("/v1/module/$testModuleId/blobs/$blobId1") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", moduleEtag2)
        }
        deleteResp.status shouldBe HttpStatusCode.OK

        http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blob1.size}}""")
        }.apply {
            status shouldBe HttpStatusCode.Created
            body<SessionInfo>().state shouldBe "active"
        }
    }

    @Test
    fun `retried finalize is idempotent`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val blobData = "idem-test".toByteArray()
        val blobHash = blobData.sha256Hex()

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobData.size}, "hashAlgorithm": "sha256", "hashHex": "$blobHash"}""")
        }.body<SessionInfo>()

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobData)
        }

        // First finalize
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
        }

        // Second finalize — should be idempotent
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.apply {
            status shouldBe HttpStatusCode.OK
            body<FinalizeInfo>().state shouldBe "complete"
        }
    }

    @Test
    fun `createSession rejects hashHex without hashAlgorithm`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 1024, "hashHex": "${"a".repeat(64)}"}""")
        }.status shouldBe HttpStatusCode.BadRequest
    }

    @Test
    fun `finalize rejects hashHex without hashAlgorithm`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, testModuleId, data = "init")

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 4}""")
        }.body<SessionInfo>()

        http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(ByteArray(4))
        }

        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"hashHex": "${"a".repeat(64)}"}""")
        }.status shouldBe HttpStatusCode.BadRequest
    }
}
