package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * Verifies P2.4: terminating a session sweeps its now-empty parent dirs (sessions/
 * and the modules/{hash}/ leaf if no committed module lives there) so session-only
 * dirs don't pile up dirents.
 */
class EmptyModuleDirCleanupTest : TestRunner() {

    @Serializable
    private data class SessionInfo(val blobId: String = "", val sessionId: String = "")

    @Test
    fun `aborting a session for a brand-new moduleId leaves no empty module dir`() = runTest2 {
        val creds = createDevice()
        val moduleId = "eu.darken.octi.swept"

        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 0}""")
        }.body<SessionInfo>()

        // Module dir was created as a side effect of session creation.
        getModulesPath(creds).listDirectoryEntries().size shouldBe 1

        http.delete("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.OK

        // After abort, the empty module dir should be gone.
        val modulesPath = getModulesPath(creds)
        if (modulesPath.exists()) {
            modulesPath.listDirectoryEntries().size shouldBe 0
        }
    }

    @Test
    fun `aborting a session for an existing committed module preserves the module dir`() = runTest2 {
        val creds = createDevice()
        val moduleId = "eu.darken.octi.kept"

        // Commit a real module first.
        writeModule(creds, moduleId, data = "x").status shouldBe HttpStatusCode.OK

        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 0}""")
        }.body<SessionInfo>()

        http.delete("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
        }.status shouldBe HttpStatusCode.OK

        // Module dir must remain — it still holds module.json + payload.blob.
        getModulesPath(creds).listDirectoryEntries().size shouldBe 1
    }
}
