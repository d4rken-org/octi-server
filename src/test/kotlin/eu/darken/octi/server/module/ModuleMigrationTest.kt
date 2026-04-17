package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import kotlin.io.path.*

class ModuleMigrationTest : TestRunner() {

    private val testModuleId = "eu.darken.octi.module.test"

    @Test
    fun `legacy module with valid module json is migrated on first read`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        // Seed legacy fixture on disk
        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes("hello-legacy".toByteArray())
        moduleDir.resolve("module.json").writeText("""{"id":"$testModuleId","source":"${creds.deviceId}"}""")

        // First read should trigger migration
        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe "hello-legacy"
            headers["X-Modified-At"] shouldNotBe null
            headers["ETag"] shouldNotBe null
            headers["ETag"]!!.shouldNotBeEmpty()
        }

        // Verify module.json was migrated to v1 schema
        // Note: schemaVersion has a default value (1) so kotlinx-serialization may omit it
        val metaText = moduleDir.resolve("module.json").readText()
        metaText.contains("\"etag\"") shouldBe true
        metaText.contains("\"documentSizeBytes\"") shouldBe true
        metaText.contains("\"moduleId\"") shouldBe true

        // Verify access.json was created
        moduleDir.resolve("access.json").exists() shouldBe true
    }

    @Test
    fun `legacy module payload blob is not modified during migration`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        val originalPayload = "original-encrypted-payload-bytes".toByteArray()
        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes(originalPayload)
        moduleDir.resolve("module.json").writeText("""{"id":"$testModuleId","source":"${creds.deviceId}"}""")

        // Read triggers migration
        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
        }

        // payload.blob must be unchanged
        moduleDir.resolve("payload.blob").readBytes() shouldBe originalPayload
    }

    @Test
    fun `synthesized etag is deterministic across reads`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes("test-data".toByteArray())
        moduleDir.resolve("module.json").writeText("""{"id":"$testModuleId","source":"${creds.deviceId}"}""")

        val etag1 = readModuleRaw(creds, testModuleId).headers["ETag"]!!

        // Read again — should get the same ETag (migrated metadata persisted)
        val etag2 = readModuleRaw(creds, testModuleId).headers["ETag"]!!
        etag2 shouldBe etag1
    }

    @Test
    fun `missing module json with present payload blob recovers`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        // Only payload.blob, no module.json
        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes("orphan-payload".toByteArray())

        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe "orphan-payload"
            headers["ETag"] shouldNotBe null
        }

        // module.json should now exist (synthesized)
        moduleDir.resolve("module.json").exists() shouldBe true
    }

    @Test
    fun `malformed module json with present payload blob recovers`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes("recover-me".toByteArray())
        moduleDir.resolve("module.json").writeText("{corrupted json garbage!!!")

        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText() shouldBe "recover-me"
            headers["ETag"] shouldNotBe null
        }
    }

    @Test
    fun `absent module returns 204 no content`() = runTest2 {
        val creds = createDevice()

        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.NoContent
            bodyAsText() shouldBe ""
        }
    }

    @Test
    fun `legacy module remains writable through legacy POST after migration`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        // Seed legacy fixture
        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes("original".toByteArray())
        moduleDir.resolve("module.json").writeText("""{"id":"$testModuleId","source":"${creds.deviceId}"}""")

        // Read triggers migration
        readModuleRaw(creds, testModuleId).apply {
            status shouldBe HttpStatusCode.OK
        }

        // Legacy POST should still work (no blobRefs)
        writeModule(creds, testModuleId, data = "updated-payload").apply {
            status shouldBe HttpStatusCode.OK
            headers["ETag"] shouldNotBe null
        }

        readModule(creds, testModuleId) shouldBe "updated-payload"
    }

    @Test
    fun `write produces new etag on each write`() = runTest2 {
        val creds = createDevice()

        writeModule(creds, testModuleId, data = "v1").apply {
            status shouldBe HttpStatusCode.OK
        }
        val etag1 = readModuleRaw(creds, testModuleId).headers["ETag"]!!

        writeModule(creds, testModuleId, data = "v2").apply {
            status shouldBe HttpStatusCode.OK
        }
        val etag2 = readModuleRaw(creds, testModuleId).headers["ETag"]!!

        etag1 shouldNotBe etag2
    }

    @Test
    fun `migration creates correct documentSizeBytes`() = runTest2 {
        val creds = createDevice()
        val modulesPath = getModulesPath(creds)
        val moduleDir = modulesPath.resolve(testModuleId.toModuleDirName())

        val payload = "twelve chars"
        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes(payload.toByteArray())
        moduleDir.resolve("module.json").writeText("""{"id":"$testModuleId","source":"${creds.deviceId}"}""")

        // Trigger migration
        readModuleRaw(creds, testModuleId)

        // Check persisted metadata via text inspection (avoids needing contextual serializers in test)
        val metaText = moduleDir.resolve("module.json").readText()
        metaText.contains("\"etag\"") shouldBe true
        metaText.contains("\"moduleId\"") shouldBe true
        metaText.contains("\"documentSizeBytes\"") shouldBe true
        metaText.contains("\"$testModuleId\"") shouldBe true
        // documentSizeBytes should match payload length
        metaText.contains("\"documentSizeBytes\": ${payload.toByteArray().size}") shouldBe true
    }
}
