package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Finalize semantics survive a server restart. HTTP can't distinguish `Success` from
 * `AlreadyComplete` (both map to 200), so the idempotent branch is asserted by calling
 * `sessionRepo.finalizeSession()` directly on the post-restart component.
 */
class BlobFinalizeIdempotencyRestartTest : TestRunner() {

    private val moduleId = "eu.darken.octi.restart.finalize"

    @Serializable
    private data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
    )

    @Test
    fun `finalized session survives restart and re-finalize returns AlreadyComplete`() {
        lateinit var capturedSessionId: String
        lateinit var capturedBlobId: String
        lateinit var capturedAccount: UUID
        val capturedDeviceId = UUID.randomUUID()
        val payload = "finalize-restart-payload".toByteArray()
        val hash = payload.sha256Hex()

        // ---- Phase 1: create + finalize session, then shut down keeping data. ----
        runTest2(keepData = true) {
            val creds = createDevice(capturedDeviceId)
            capturedAccount = UUID.fromString(creds.account)

            val session = http.post("/v1/module/$moduleId/blob-sessions") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("""{"sizeBytes": ${payload.size}, "hashAlgorithm": "sha256", "hashHex": "$hash"}""")
            }.body<SessionInfo>()
            http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                header("Upload-Offset", "0")
                contentType(ContentType.Application.OctetStream)
                setBody(payload)
            }
            val finalizeResponse = http.post("/v1/module/$moduleId/blob-sessions/${session.sessionId}/finalize") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            finalizeResponse.status shouldBe HttpStatusCode.OK

            capturedSessionId = session.sessionId
            capturedBlobId = session.blobId
        }

        // ---- Phase 2: relaunch on the same dataPath, different port. ----
        runTest2(
            appConfig = baseConfig.copy(port = baseConfig.port + 1),
            keepData = false,
        ) {
            // Direct call because HTTP can't distinguish Success vs AlreadyComplete.
            val result = runBlocking {
                component.sessionRepo().finalizeSession(
                    sessionId = capturedSessionId,
                    accountId = capturedAccount,
                    deviceId = capturedDeviceId,
                    moduleId = moduleId,
                    hashAlgorithm = null,
                    hashHex = null,
                )
            }

            (result is UploadSessionRepo.FinalizeResult.AlreadyComplete) shouldBe true
            (result as UploadSessionRepo.FinalizeResult.AlreadyComplete).blobId shouldBe capturedBlobId
            result.sizeBytes shouldBe payload.size.toLong()
        }
    }
}
