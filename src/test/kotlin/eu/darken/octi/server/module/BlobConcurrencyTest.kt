package eu.darken.octi.server.module

import eu.darken.octi.*
import eu.darken.octi.server.device.DeviceKey
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.security.MessageDigest
import java.util.*
import kotlin.io.path.exists

/**
 * Probes the open-fd-after-unlink contract at the repo level. HTTP streaming is unreliable
 * here because Ktor socket buffers may let the server finish copyTo before DELETE runs,
 * defeating the intended race.
 *
 * On POSIX: opening a file via [java.nio.file.Files.newInputStream] gives a descriptor
 * pointing at the inode; the inode survives unlink as long as the descriptor is held.
 * Skipped on non-POSIX platforms (Windows behaviour is explicitly out of scope).
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class BlobConcurrencyTest : TestRunner() {

    private val moduleId = "eu.darken.octi.concurrency"

    @Serializable
    private data class SessionInfo(val blobId: String = "", val sessionId: String = "")

    @Test
    fun `open handle survives DELETE and yields correct bytes`() = runTest2 {
        val creds = createDevice()
        val blobBytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        val hash = blobBytes.sha256Hex()

        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${blobBytes.size}, "hashAlgorithm": "sha256", "hashHex": "$hash"}""")
        }.body<SessionInfo>()
        http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(blobBytes)
        }
        http.post("/v1/module/$moduleId/blob-sessions/${session.sessionId}/finalize") {
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val commitResponse = http.put("/v1/module/$moduleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }
        val etag = commitResponse.headers["ETag"]!!.trim('"')

        val accountId = UUID.fromString(creds.account)
        val target = component.deviceRepo().getDevice(DeviceKey(accountId, creds.deviceId))
            ?: error("device not loaded")

        // Open the handle BEFORE delete — this captures a descriptor that must survive unlink.
        val handle = component.moduleRepo().openBlobHandle(target, moduleId, session.blobId)
            ?: error("openBlobHandle returned null")

        // Locate the on-disk directory so we can verify post-delete cleanup.
        val moduleDir = getModulesPath(creds).resolve(moduleId.toModuleDirName())
        val storageKey = Regex(""""storageKey"\s*:\s*"([^"]+)"""")
            .find(moduleDir.resolve("module.json").toFile().readText())!!
            .groupValues[1]
        val blobDir = moduleDir.resolve("blobs").resolve(storageKey.take(4)).resolve(storageKey)
        blobDir.exists() shouldBe true

        // Now delete.
        val deleteResponse = http.delete("/v1/module/$moduleId/blobs/${session.blobId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-Match", "\"$etag\"")
        }
        deleteResponse.status shouldBe HttpStatusCode.OK

        // Drain the still-open handle; verify bytes match.
        val digest = MessageDigest.getInstance("SHA-256")
        val drained = handle.use { h ->
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val n = h.stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
                total += n
            }
            total
        }
        @OptIn(ExperimentalStdlibApi::class)
        val drainedHash = digest.digest().toHexString()

        drained shouldBe blobBytes.size.toLong()
        drainedHash shouldBe hash

        // Blob dir is removed by the async cleanup — poll briefly.
        var gone = false
        repeat(30) {
            if (!blobDir.exists()) {
                gone = true
                return@repeat
            }
            Thread.sleep(100)
        }
        gone shouldBe true
    }
}
