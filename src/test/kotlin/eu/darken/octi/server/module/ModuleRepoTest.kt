package eu.darken.octi.server.module

import eu.darken.octi.TestRunner
import eu.darken.octi.createDevice
import eu.darken.octi.readModule
import eu.darken.octi.writeModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries

@OptIn(ExperimentalPathApi::class)
class ModuleRepoTest : TestRunner() {

    @Test
    fun `module data can expire`() = runTest2(
        appConfig = baseConfig.copy(
            moduleExpiration = Duration.ofSeconds(2),
            moduleGCInterval = Duration.ofSeconds(1),
        ),
    ) {
        val creds = createDevice()
        writeModule(creds, "abc", data = "test")
        readModule(creds, "abc") shouldBe "test"
        Thread.sleep(config.moduleExpiration.toMillis() + 1000)
        readModule(creds, "abc") shouldBe ""
    }

    @Test
    fun `access time writes are coalesced within debounce window`() = runTest2 {
        val creds = createDevice()
        writeModule(creds, "abc", data = "test")

        val moduleDir = getModulesPath(creds).listDirectoryEntries().first()
        val accessFile = moduleDir.resolve("access.json")
        val initialMtime = accessFile.getLastModifiedTime().toMillis()

        // Rapid reads should bump the in-memory shadow but never touch access.json
        // while the 30s debounce window is open.
        repeat(10) { readModule(creds, "abc") shouldBe "test" }

        accessFile.getLastModifiedTime().toMillis() shouldBe initialMtime
    }
}