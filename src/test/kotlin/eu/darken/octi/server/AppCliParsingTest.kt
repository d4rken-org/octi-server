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

    @Test
    fun `--cors-allowed-origins defaults to the official octi-web hosting origins`() {
        // OOB UX: a fresh self-hosted server can serve the official SPA without any flag.
        // Self-hosters who want a tighter allowlist override via the flag (incl. empty).
        val origins = App.parseConfig(baseArgs).corsAllowedOrigins
        origins shouldBe setOf(
            "https://web.octi.darken.eu",
            "https://d4rken.github.io",
            "https://d4rken-org.github.io",
        )
    }

    @Test
    fun `--cors-allowed-origins= (empty) disables browser access`() {
        // Explicit empty value overrides the baked-in defaults. The split-and-filter
        // pipeline naturally yields an empty set rather than crashing on empty input.
        App.parseConfig(baseArgs + "--cors-allowed-origins=").corsAllowedOrigins shouldBe emptySet()
    }

    @Test
    fun `--cors-allowed-origins parses a single origin`() {
        val cfg = App.parseConfig(baseArgs + "--cors-allowed-origins=https://web.octi.darken.eu")
        cfg.corsAllowedOrigins shouldBe setOf("https://web.octi.darken.eu")
    }

    @Test
    fun `--cors-allowed-origins parses comma-separated origins with whitespace`() {
        val cfg = App.parseConfig(baseArgs + "--cors-allowed-origins=https://web.octi.darken.eu, http://localhost:5173")
        cfg.corsAllowedOrigins shouldBe setOf("https://web.octi.darken.eu", "http://localhost:5173")
    }

    @Test
    fun `--cors-allowed-origins rejects bare host without scheme`() {
        val ex = shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--cors-allowed-origins=web.octi.darken.eu")
        }
        ex.message!! shouldContain "--cors-allowed-origins"
    }

    @Test
    fun `--cors-allowed-origins rejects trailing slash`() {
        shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--cors-allowed-origins=https://web.octi.darken.eu/")
        }
    }

    @Test
    fun `--cors-allowed-origins rejects wildcard`() {
        shouldThrow<IllegalArgumentException> {
            App.parseConfig(baseArgs + "--cors-allowed-origins=*")
        }
    }

    @Test
    fun `--cors-allowed-origins rejects duplicate flags`() {
        shouldThrow<IllegalArgumentException> {
            App.parseConfig(
                baseArgs +
                    "--cors-allowed-origins=https://a.example" +
                    "--cors-allowed-origins=https://b.example"
            )
        }
    }
}
