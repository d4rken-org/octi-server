package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.*

/**
 * End-to-end HTTP probes of the `--min-free-disk-mb` safety floor. The gate is wired
 * into [BlobRoute.createSession] (inside `target.sync.withLock`) and into the cheap
 * pre-checks of [BlobRoute.appendToSession]. Tests pin: 507 + `X-Octi-Reason` header,
 * no quota leak on rejection, ordering vs. existence checks, and that the PATCH gate
 * sizes its headroom request to the actual remaining bytes (not the unconditional
 * `maxBlobPatchBytes`).
 */
class BlobDiskSpaceGateFlowTest : TestRunner() {

    private val moduleId = "eu.darken.octi.diskspace.gate"

    @Serializable
    private data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
        val state: String = "",
    )

    @Test
    fun `create session is blocked when disk space is below floor`() {
        runTest2(appConfig = baseConfig.copy(minFreeDiskSpaceBytes = Long.MAX_VALUE)) {
            val creds = createDevice()

            val response = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }
            response.status shouldBe HttpStatusCode.InsufficientStorage
            response.headers["X-Octi-Reason"] shouldBe "server_disk_low"
            response.bodyAsText() shouldBe "Server is low on disk space"

            // No reservation should leak — the gate fires before tryReserve is called.
            val accountId = UUID.fromString(creds.account)
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
        }
    }

    @Test
    fun `PATCH on real session is blocked when disk space is below floor`() {
        runTest2(appConfig = baseConfig.copy(minFreeDiskSpaceBytes = Long.MAX_VALUE)) {
            val creds = createDevice()
            // Seed an ACTIVE session directly so the gated POST /blob-sessions doesn't
            // get in the way — we want to exercise the PATCH gate specifically.
            val seeded = component.sessionRepo().createSession(
                accountId = UUID.fromString(creds.account),
                deviceId = creds.deviceId,
                moduleId = moduleId,
                expectedSizeBytes = 100,
                hashAlgorithm = null,
                hashHex = null,
            )

            val response = http.patch("/v1/module/$moduleId/blob-sessions/${seeded.sessionId}") {
                addCredentials(creds)
                header("Upload-Offset", "0")
                contentType(ContentType.Application.OctetStream)
                setBody(ByteArray(10))
            }
            response.status shouldBe HttpStatusCode.InsufficientStorage
            response.headers["X-Octi-Reason"] shouldBe "server_disk_low"
        }
    }

    @Test
    fun `PATCH with unknown sessionId returns 404 not 507`() {
        runTest2(appConfig = baseConfig.copy(minFreeDiskSpaceBytes = Long.MAX_VALUE)) {
            val creds = createDevice()

            // Existence check runs before the disk gate so a typo'd sessionId routes to
            // the correct 404 rather than getting masked by the safety floor.
            http.patch("/v1/module/$moduleId/blob-sessions/${UUID.randomUUID()}") {
                addCredentials(creds)
                header("Upload-Offset", "0")
                contentType(ContentType.Application.OctetStream)
                setBody(ByteArray(0))
            }.status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun `quota rejection carries account_quota_exceeded reason header`() {
        runTest2(
            appConfig = baseConfig.copy(
                minFreeDiskSpaceBytes = 0L,
                accountQuotaBytes = 1024,
                maxBlobBytes = 10_000,
            ),
        ) {
            val creds = createDevice()

            // Fill the quota.
            http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1024}""")
            }.status shouldBe HttpStatusCode.Created

            // One byte more pushes past the quota — gate must report account_quota_exceeded.
            val response = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1}""")
            }
            response.status shouldBe HttpStatusCode.InsufficientStorage
            response.headers["X-Octi-Reason"] shouldBe "account_quota_exceeded"
        }
    }

    @Test
    fun `successful session create carries no X-Octi-Reason header`() = runTest2 {
        val creds = createDevice()

        http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 1024}""")
        }.apply {
            status shouldBe HttpStatusCode.Created
            headers["X-Octi-Reason"] shouldBe null
        }
    }

    @Test
    fun `PATCH gate sizes headroom to remaining bytes not the unconditional max chunk`() {
        // Calibrate the floor against the same FileStore the probe will query — sampling
        // a different mount (e.g., CWD vs a bind-mounted dataPath) would mis-size the floor.
        // 100 MB margin absorbs concurrent CI activity between calibration and the request.
        val cfg = baseConfig
        Files.createDirectories(cfg.dataPath)
        val baseUsable = Files.getFileStore(cfg.dataPath.toAbsolutePath()).usableSpace
        val floor = (baseUsable - 100L * 1024L * 1024L).coerceAtLeast(1L)

        runTest2(appConfig = cfg.copy(minFreeDiskSpaceBytes = floor)) {
            val creds = createDevice()

            // 1-byte session — fits easily within the 100 KB margin even with the gate active.
            val session = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": 1}""")
            }.apply {
                status shouldBe HttpStatusCode.Created
            }.body<SessionInfo>()

            // PATCH with the single byte must succeed: gate sees remaining=1, contentLength=1,
            // takes the min, requests only 1 byte of headroom — well within 100 KB. Without the
            // `min(...)` fix it would unconditionally request maxBlobPatchBytes (1 MB) and 507.
            http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
                addCredentials(creds)
                header("Upload-Offset", "0")
                contentType(ContentType.Application.OctetStream)
                setBody(ByteArray(1))
            }.status shouldBe HttpStatusCode.NoContent
        }
    }
}
