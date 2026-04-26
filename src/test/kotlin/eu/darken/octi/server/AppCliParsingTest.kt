package eu.darken.octi.server

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class AppCliParsingTest {

    private val baseArgs = arrayOf("--datapath=/tmp/cli-parse-test")

    @Test
    fun `defaults are used when --min-free-disk-mb is absent`() {
        val cfg = App.parseConfig(baseArgs)
        cfg.minFreeDiskSpaceBytes shouldBe 500L * 1024 * 1024
    }

    @Test
    fun `--min-free-disk-mb scales by 1 MB`() {
        val cfg = App.parseConfig(baseArgs + "--min-free-disk-mb=200")
        cfg.minFreeDiskSpaceBytes shouldBe 200L * 1024 * 1024
    }

    @Test
    fun `--min-free-disk-mb rejects non-numeric values`() {
        val ex = shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--min-free-disk-mb=abc")
        }
        ex.message!! shouldContain "--min-free-disk-mb"
    }

    @Test
    fun `--min-free-disk-mb rejects zero`() {
        // The shared parseSizeFlag helper enforces value > 0 across all size flags.
        shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--min-free-disk-mb=0")
        }
    }

    @Test
    fun `--min-free-disk-mb rejects negative values`() {
        shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--min-free-disk-mb=-1")
        }
    }
}
