package eu.darken.octi.server.regression

import eu.darken.octi.TestRunner
import eu.darken.octi.addCredentials
import eu.darken.octi.createDevice
import eu.darken.octi.getDevices
import eu.darken.octi.readModule
import eu.darken.octi.readModuleRaw
import eu.darken.octi.writeModule
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

/**
 * End-to-end checks that pin a legacy client-version label on requests against the new server,
 * plus a synthetic-fixture replay that exercises StartupRecoveryService at production-like scale
 * without referencing real production data.
 *
 * Mirrors the cutover risks for ~700 production clients on the v0.8.1 client release: legacy
 * devices must still register, write, and read modules; they must not silently corrupt new
 * server state; and the new server's recovery loop must boot cleanly against a large tree.
 */
class CrossVersionFlowTest : TestRunner() {

    private val legacyVersion = "octi/0.8.1"

    @Test
    fun `legacy client registers and writes module without blob refs`() = runTest2 {
        val creds = createDevice(version = legacyVersion)
        val response = writeModule(creds, "eu.darken.octi.module.test", data = "hello")
        response.status shouldBe HttpStatusCode.OK

        val device = getDevices(creds).devices.single()
        device.version shouldBe legacyVersion
        readModule(creds, "eu.darken.octi.module.test") shouldBe "hello"
    }

    @Test
    fun `legacy client GET on module written via PUT-with-blob-refs returns the document`() = runTest2 {
        val moduleId = "eu.darken.octi.module.mixed"
        val newCreds = createDevice(version = "octi/1.0.0")
        val legacyCreds = createDevice(newCreds) // legacy paired into same account via share

        val doc = "post-blob-doc".toByteArray()

        // New client commits via PUT with an empty blobRefs list — the wire path the legacy
        // client never speaks. The blobRefs array can be empty here; the regression we care
        // about is "GET still returns the document" regardless of how it was written.
        http.put("/v1/module/$moduleId") {
            url.parameters.append("device-id", newCreds.deviceId.toString())
            addCredentials(newCreds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${Base64.getEncoder().encodeToString(doc)}", "blobRefs": []}""")
        }.status shouldBe HttpStatusCode.OK

        // Legacy client (different device, same account) reads it via the legacy GET — the
        // route old clients have always used. Must succeed unchanged.
        readModuleRaw(legacyCreds, moduleId, deviceId = newCreds.deviceId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe "post-blob-doc"
        }
    }

    @Test
    fun `legacy POST overwrites a same-device module previously written via PUT with no blob refs`() = runTest2 {
        // Realistic downgrade scenario: same device, same install, was on a new client that
        // wrote via PUT (blobRefs=[]), now on the legacy client. POST should still succeed
        // because no actual blob refs are attached.
        val moduleId = "eu.darken.octi.module.legacyoverwrite"
        val creds = createDevice(version = "octi/1.0.0")

        http.put("/v1/module/$moduleId") {
            url.parameters.append("device-id", creds.deviceId.toString())
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${Base64.getEncoder().encodeToString("v1".toByteArray())}", "blobRefs": []}""")
        }.status shouldBe HttpStatusCode.OK

        // Now the same install POSTs as a legacy client.
        writeModule(creds, moduleId, data = "legacy-overwrite").status shouldBe HttpStatusCode.OK
        readModule(creds, moduleId) shouldBe "legacy-overwrite"
    }

    @Test
    fun `optional prod data replay boots cleanly via StartupRecoveryService`() {
        // Local-only escape hatch: developers run this with `OCTI_PROD_FIXTURE=/path/to/zip
        // ./gradlew test`. The fixture is never committed and never read in CI — the env var
        // must remain unset on every CI runner. Use the synthetic replay (below) for CI.
        val fixturePath = System.getenv("OCTI_PROD_FIXTURE")
        Assumptions.assumeTrue(
            fixturePath != null && File(fixturePath).isFile,
            "OCTI_PROD_FIXTURE not set or file missing — skipping local prod replay",
        )

        runTest2(
            seed = { cfg ->
                val target = cfg.dataPath.toFile()
                target.mkdirs()
                // Inline unzip — keeps the test self-contained without a build dependency.
                java.util.zip.ZipFile(File(fixturePath!!)).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        val outFile = File(target, entry.name).normalize()
                        if (!outFile.toPath().startsWith(target.toPath())) {
                            error("zip entry escapes target dir: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input -> outFile.outputStream().use(input::copyTo) }
                        }
                    }
                }
                // Some prod dumps wrap content under a top-level zdatapath-prod/ folder.
                val nested = File(target, "zdatapath-prod")
                if (nested.isDirectory) {
                    nested.listFiles()?.forEach { it.renameTo(File(target, it.name)) }
                    nested.delete()
                }
            },
        ) {
            val statusResp = http.get("/v1/status")
            statusResp.status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun `synthetic 500-account replay boots cleanly via StartupRecoveryService`() {
        var sentinel: SyntheticDataFixture.Sentinel? = null
        runTest2(
            seed = { cfg -> sentinel = SyntheticDataFixture.seed(cfg, accountCount = 500) },
        ) {
            // Hit /v1/status to confirm the server actually came up after recovery walked the tree.
            val statusResp = http.get("/v1/status")
            statusResp.status shouldBe HttpStatusCode.OK

            // Sentinel account must be reachable: getAccountPath finds it on disk.
            val s = requireNotNull(sentinel) { "seed did not run" }
            val accountPath = config.dataPath.resolve("accounts").resolve(s.firstAccount.toString())
            accountPath.toFile().exists() shouldBe true

            val devicePath = accountPath.resolve("devices").resolve(s.firstDevice.toString())
            devicePath.toFile().exists() shouldBe true
        }
    }
}
