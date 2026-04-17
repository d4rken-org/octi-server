package eu.darken.octi.server.module

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModuleIdTest {

    @Test
    fun `toModuleDirName is stable for known input`() {
        "eu.darken.octi.module.test".toModuleDirName() shouldBe
            "77a5087bcfc411fc04a0e82203e4076db481df72"
    }

    @Test
    fun `toModuleDirName produces lowercase 40-char hex`() {
        val out = "any-module-id".toModuleDirName()
        out.length shouldBe 40
        out.matches(Regex("^[0-9a-f]{40}$")) shouldBe true
    }
}
