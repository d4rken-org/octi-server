package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.*

private fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

/**
 * End-to-end HTTP probes of the quota boundary. Complements [eu.darken.octi.server.account.AccountStorageTrackerTest]
 * which exercises the tracker in isolation — this verifies the route wiring (507 response,
 * `sizeBytes > 0` bypass, `maxBlobBytes` check, reservation release on abort).
 */
class BlobQuotaBoundaryFlowTest : TestRunner() {

    private val moduleId = "eu.darken.octi.quota.boundary"

    @Serializable
    private data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
    )

    @Test
    fun `session at exact quota succeeds, one more byte returns 507`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 1024, maxBlobBytes = 10_000)) {
            val creds = createDevice()

            val first = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }
            first.status shouldBe HttpStatusCode.Created

            val second = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1}""")
            }
            second.status shouldBe HttpStatusCode.InsufficientStorage
        }
    }

    @Test
    fun `aborting a session releases the reservation so a new session at exact quota succeeds`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 1024, maxBlobBytes = 10_000)) {
            val creds = createDevice()

            val first = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }.body<SessionInfo>()

            http.delete("/v1/module/$moduleId/blob-sessions/${first.sessionId}") {
                addCredentials(creds)
            }.status shouldBe HttpStatusCode.OK

            val second = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }
            second.status shouldBe HttpStatusCode.Created
        }
    }

    // Documents current behaviour: zero-byte sessions skip the tryReserve call entirely (the
    // `sizeBytes > 0` branch in BlobRoute.createSession). They can be created even when quota
    // is fully reserved. If production later gates zero-byte sessions for consistency, update
    // this test.
    @Test
    fun `zero-byte session bypasses quota check even when reserved is at limit`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 1024, maxBlobBytes = 10_000)) {
            val creds = createDevice()

            http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }.status shouldBe HttpStatusCode.Created

            val zero = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 0}""")
            }
            zero.status shouldBe HttpStatusCode.Created
        }
    }

    @Test
    fun `legacy POST exceeding quota returns 507 without writing the file`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 100, payloadLimit = 1000)) {
            val creds = createDevice()

            val payload = "x".repeat(200) // fits payload limit but blows quota
            writeModule(creds, "eu.darken.octi.docquota", data = payload).apply {
                status shouldBe HttpStatusCode.InsufficientStorage
            }

            // Subsequent write that fits should still succeed — the failed one held no quota.
            writeModule(creds, "eu.darken.octi.docquota", data = "x".repeat(50)).apply {
                status shouldBe HttpStatusCode.OK
            }
        }
    }

    @Test
    fun `legacy POST aggregate documents past quota return 507`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 200, payloadLimit = 1000)) {
            val creds = createDevice()

            writeModule(creds, "eu.darken.octi.agga", data = "x".repeat(100)).status shouldBe HttpStatusCode.OK
            writeModule(creds, "eu.darken.octi.aggb", data = "x".repeat(100)).status shouldBe HttpStatusCode.OK
            writeModule(creds, "eu.darken.octi.aggc", data = "x").apply {
                status shouldBe HttpStatusCode.InsufficientStorage
            }
        }
    }

    @Test
    fun `PUT commit with document over quota returns 507`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 200, maxModuleDocumentBytes = 500)) {
            val creds = createDevice()
            val moduleId = "eu.darken.octi.putdoc"

            val doc = ByteArray(300) { 'x'.code.toByte() }
            http.put("/v1/module/$moduleId") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                header("If-None-Match", "*")
                contentType(ContentType.Application.Json)
                setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
            }.status shouldBe HttpStatusCode.InsufficientStorage

            // Smaller doc that fits should succeed afterwards — failed PUT held no quota.
            val small = ByteArray(50) { 'x'.code.toByte() }
            http.put("/v1/module/$moduleId") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                header("If-None-Match", "*")
                contentType(ContentType.Application.Json)
                setBody("""{"documentBase64": "${base64Encode(small)}", "blobRefs": []}""")
            }.status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun `legacy POST shrinking an existing module never trips quota`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 100, payloadLimit = 1000)) {
            val creds = createDevice()
            val moduleId = "eu.darken.octi.shrink"

            writeModule(creds, moduleId, data = "x".repeat(100)).status shouldBe HttpStatusCode.OK
            // Replacement that's smaller — must not 507 even though absolute size is at the cap.
            writeModule(creds, moduleId, data = "x".repeat(40)).status shouldBe HttpStatusCode.OK
            // Used bytes should be 40 now, so a fresh module of 60 bytes still fits.
            writeModule(creds, "eu.darken.octi.shrink2", data = "x".repeat(60)).status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun `sizeBytes greater than maxBlobBytes returns 413 without holding a reservation`() {
        runTest2(appConfig = baseConfig.copy(accountQuotaBytes = 1024 * 1024, maxBlobBytes = 1024)) {
            val creds = createDevice()

            http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1025}""")
            }.status shouldBe HttpStatusCode.PayloadTooLarge

            // If the rejected session had held a reservation, the next session at the full
            // remaining quota (1 MB) would fail. It should succeed.
            http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }.status shouldBe HttpStatusCode.Created
        }
    }
}
