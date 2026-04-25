package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Pins two recovery / concurrency assumptions that the implementation relies on:
 *
 * - The runtime read path (GET /v1/module/{id}) survives a corrupted `module.json` as long as
 *   `payload.blob` is intact: [ModuleRepo.loadOrMigrateMeta] synthesises new meta from the blob
 *   and the client still gets the bytes.
 * - `ModuleLifecycleService.commitModule` serialises concurrent commits to the same module via
 *   the per-device `target.sync` mutex, so a flurry of `If-None-Match: *` PUTs results in
 *   exactly one 200 and the rest 412 (instead of two ETag-conflicting writes landing).
 */
class ModuleResilienceFlowTest : TestRunner() {

    private val testModuleId = "eu.darken.octi.module.test"

    private fun base64Encode(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)

    @Test
    fun `corrupted module json with valid payload blob recovers on GET`() = runTest2 {
        val creds = createDevice()

        // Seed a normal module via the legacy POST path: creates module.json + payload.blob.
        val originalBytes = "hello-payload".toByteArray()
        writeModule(creds, testModuleId, data = String(originalBytes)).status shouldBe HttpStatusCode.OK

        // Corrupt module.json on disk while leaving payload.blob untouched.
        val moduleDir = getModulesPath(creds).resolve(testModuleId.toModuleDirName())
        val metaFile = moduleDir.resolve("module.json")
        metaFile.toFile().writeText("THIS_IS_NOT_JSON")

        // GET must succeed: the recovery path synthesises a fresh ModuleMeta from the blob,
        // returns the bytes, and the freshly-persisted module.json must be parseable v1 JSON.
        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe String(originalBytes)
        }

        val recovered = metaFile.toFile().readText()
        recovered shouldContain "\"schemaVersion\""
        recovered shouldContain "\"moduleId\""
    }

    @Test
    fun `concurrent commits to the same module serialise to one success`() = runTest2 {
        val creds = createDevice()

        val payload = "concurrent-commit".toByteArray()
        val body = """{"documentBase64": "${base64Encode(payload)}", "blobRefs": []}"""
        val concurrency = 8

        val responses = coroutineScope {
            (1..concurrency).map {
                async {
                    http.put("/v1/module/$testModuleId") {
                        url { parameters.append("device-id", creds.deviceId.toString()) }
                        addCredentials(creds)
                        header("If-None-Match", "*")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            }.awaitAll()
        }

        val ok = responses.count { it.status == HttpStatusCode.OK }
        val precondFailed = responses.count { it.status == HttpStatusCode.PreconditionFailed }

        ok shouldBe 1
        precondFailed shouldBe (concurrency - 1)

        // Final read should reflect exactly the body we PUT, with one stable ETag.
        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe String(payload)
            headers["ETag"] shouldBe responses.first { it.status == HttpStatusCode.OK }.headers["ETag"]
        }
    }
}
