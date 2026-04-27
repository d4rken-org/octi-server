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

    @Test
    fun `--min-free-disk-mb rejects duplicate flags`() {
        val ex = shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--min-free-disk-mb=100" + "--min-free-disk-mb=200")
        }
        ex.message!! shouldContain "--min-free-disk-mb"
    }

    @Test
    fun `--min-free-disk-mb rejects values that overflow when scaled`() {
        // value * 1_048_576 must fit in a Long; Long.MAX_VALUE / (1024*1024) ≈ 8.8e12.
        val tooBig = (Long.MAX_VALUE / (1024L * 1024L)) + 1L
        shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--min-free-disk-mb=$tooBig")
        }
    }

    @Test
    fun `--disable-rate-limits also disables the per-account limiter`() {
        // Pre-fix: only `rateLimit` (the IP-based limiter) was nullified; AccountRateLimiter
        // kept enforcing config.accountRateLimit. Now both are disabled by the same flag.
        val cfg = App.parseConfig(baseArgs + "--disable-rate-limits")
        cfg.rateLimit shouldBe null
        cfg.accountRateLimit shouldBe 0
    }

    @Test
    fun `without --disable-rate-limits accountRateLimit keeps its default`() {
        val cfg = App.parseConfig(baseArgs)
        // AccountRateLimiter.acquire treats <= 0 as disabled, so the default must be > 0.
        (cfg.accountRateLimit > 0) shouldBe true
    }
}
