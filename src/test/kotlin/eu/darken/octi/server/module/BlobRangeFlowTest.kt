package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Verifies HTTP Range support on `GET /v1/module/{moduleId}/blobs/{blobId}`:
 * Accept-Ranges + ETag are always present, satisfiable single-range produces 206 with the
 * correct slice, unsatisfiable ranges produce 416 with the canonical Content-Range, and
 * multi-range requests fall back to a 200 full body (RFC 7233 §4.3 — we don't support
 * `multipart/byteranges`).
 */
class BlobRangeFlowTest : TestRunner() {

    private val testModuleId = "eu.darken.octi.module.test"

    @Serializable
    private data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
        val offsetBytes: Long = 0,
        val expiresAt: String = "",
        val state: String = "",
    )

    private fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    /**
     * Pushes a finalized blob into the account and returns its server-side blobId. The
     * payload has a deterministic byte at each offset (`offset.toByte()`) so range slicing
     * is easy to verify.
     */
    private suspend fun TestEnvironment.commitBlob(
        creds: Credentials,
        payload: ByteArray,
    ): String {
        // Bootstrap the module so subsequent commits have an ETag to match against.
        val createResp = http.post("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode("init".toByteArray())}", "blobRefs": []}""")
        }
        createResp.status shouldBe HttpStatusCode.OK
        val initialEtag = createResp.headers["ETag"]!!.trim('"')

        val session = http.post("/v1/module/$testModuleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${payload.size}, "hashAlgorithm": "sha256", "hashHex": "${payload.sha256Hex()}"}""")
        }.body<SessionInfo>()

        if (payload.isNotEmpty()) {
            http.patch("/v1/module/$testModuleId/blob-sessions/${session.sessionId}") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                header("Upload-Offset", "0")
                contentType(ContentType.Application.OctetStream)
                setBody(payload)
            }
        }
        http.post("/v1/module/$testModuleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.status shouldBe HttpStatusCode.OK

        http.put("/v1/module/$testModuleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", initialEtag)
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode("doc".toByteArray())}", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }.status shouldBe HttpStatusCode.OK

        return session.blobId
    }

    private fun deterministicPayload(size: Int): ByteArray = ByteArray(size) { it.toByte() }

    @Test
    fun `whole-body GET advertises Accept-Ranges and a strong ETag`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers[HttpHeaders.AcceptRanges] shouldBe "bytes"
            headers[HttpHeaders.ETag] shouldNotBe null
            val body = bodyAsBytes()
            body.size shouldBe payload.size
            body.contentEquals(payload) shouldBe true
        }
    }

    @Test
    fun `prefix range returns 206 with correct slice`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=0-99")
        }.apply {
            status shouldBe HttpStatusCode.PartialContent
            headers[HttpHeaders.ContentRange] shouldBe "bytes 0-99/500"
            val body = bodyAsBytes()
            body.size shouldBe 100
            body.contentEquals(payload.copyOfRange(0, 100)) shouldBe true
        }
    }

    @Test
    fun `mid range returns 206 with correct slice`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=100-199")
        }.apply {
            status shouldBe HttpStatusCode.PartialContent
            headers[HttpHeaders.ContentRange] shouldBe "bytes 100-199/500"
            val body = bodyAsBytes()
            body.size shouldBe 100
            body.contentEquals(payload.copyOfRange(100, 200)) shouldBe true
        }
    }

    @Test
    fun `suffix range returns last N bytes`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=-50")
        }.apply {
            status shouldBe HttpStatusCode.PartialContent
            headers[HttpHeaders.ContentRange] shouldBe "bytes 450-499/500"
            val body = bodyAsBytes()
            body.size shouldBe 50
            body.contentEquals(payload.copyOfRange(450, 500)) shouldBe true
        }
    }

    @Test
    fun `open-ended range bytes=N- returns from N to end`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=400-")
        }.apply {
            status shouldBe HttpStatusCode.PartialContent
            headers[HttpHeaders.ContentRange] shouldBe "bytes 400-499/500"
            val body = bodyAsBytes()
            body.size shouldBe 100
            body.contentEquals(payload.copyOfRange(400, 500)) shouldBe true
        }
    }

    @Test
    fun `range past EOF returns 416 with canonical Content-Range`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=600-700")
        }.apply {
            status shouldBe HttpStatusCode.RequestedRangeNotSatisfiable
            headers[HttpHeaders.ContentRange] shouldBe "bytes */500"
        }
    }

    @Test
    fun `bytes=0-0 against zero-byte blob returns 416`() = runTest2 {
        val creds = createDevice()
        val blobId = commitBlob(creds, ByteArray(0))

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=0-0")
        }.apply {
            status shouldBe HttpStatusCode.RequestedRangeNotSatisfiable
            headers[HttpHeaders.ContentRange] shouldBe "bytes */0"
        }
    }

    @Test
    fun `multi-range request falls back to 200 full body`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(500)
        val blobId = commitBlob(creds, payload)

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.Range, "bytes=0-99,200-299")
        }.apply {
            status shouldBe HttpStatusCode.OK
            val body = bodyAsBytes()
            body.size shouldBe payload.size
            body.contentEquals(payload) shouldBe true
        }
    }

    @Test
    fun `If-None-Match matching the strong ETag returns 304`() = runTest2 {
        val creds = createDevice()
        val payload = deterministicPayload(100)
        val blobId = commitBlob(creds, payload)

        val firstResponse = http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }
        firstResponse.status shouldBe HttpStatusCode.OK
        val etag = firstResponse.headers[HttpHeaders.ETag]!!

        http.get("/v1/module/$testModuleId/blobs/$blobId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header(HttpHeaders.IfNoneMatch, etag)
        }.apply {
            status shouldBe HttpStatusCode.NotModified
        }
    }
}

private suspend fun HttpResponse.bodyAsBytes(): ByteArray = readRawBytes()
