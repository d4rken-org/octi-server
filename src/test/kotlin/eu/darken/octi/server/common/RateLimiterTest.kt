package eu.darken.octi.server.common

import eu.darken.octi.TestRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import java.time.Duration

class RateLimiterTest : TestRunner() {

    @Test
    fun `test rate limit allows requests within limit`() = runTest2(
        appConfig = baseConfig.copy(
            rateLimit = RateLimitConfig(limit = 2, resetTime = Duration.ofSeconds(5))
        )
    ) {
        http.get("/v1/status").apply {
            status shouldBe HttpStatusCode.OK
        }
        Thread.sleep(100)

        http.get("/v1/status").apply {
            status shouldBe HttpStatusCode.OK
        }
        Thread.sleep(100)

        http.get("/v1/status").apply {
            status shouldBe HttpStatusCode.TooManyRequests
            headers[HttpHeaders.RetryAfter] shouldNotBe null
            bodyAsText() shouldBe "Rate limit exceeded. Try again later."
        }
    }

    @Test
    fun `test rate limit resets after time window`() = runTest2(
        appConfig = baseConfig.copy(
            rateLimit = RateLimitConfig(limit = 2, resetTime = Duration.ofSeconds(5))
        )
    ) {
        repeat(2) {
            http.get("/v1/status").apply {
                status shouldBe HttpStatusCode.OK
            }
            Thread.sleep(100)
        }

        http.get("/v1/status").apply {
            status shouldBe HttpStatusCode.TooManyRequests
        }
        Thread.sleep(100)

        Thread.sleep(5100)

        http.get("/v1/status").apply {
            status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun `test different X-Real-IP from trusted proxy have separate rate limits`() = runTest2(
        appConfig = baseConfig.copy(
            rateLimit = RateLimitConfig(limit = 2, resetTime = Duration.ofSeconds(5))
        )
    ) {
        // Tests connect via loopback (trusted proxy), so X-Real-IP is used
        repeat(2) {
            http.get("/v1/status") {
                header("X-Real-IP", "192.168.1.1")
            }.apply {
                status shouldBe HttpStatusCode.OK
            }
            Thread.sleep(100)
        }

        // 192.168.1.1 is exhausted
        http.get("/v1/status") {
            header("X-Real-IP", "192.168.1.1")
        }.apply {
            status shouldBe HttpStatusCode.TooManyRequests
        }
        Thread.sleep(100)

        // 192.168.1.2 should still have its own limit
        repeat(2) {
            http.get("/v1/status") {
                header("X-Real-IP", "192.168.1.2")
            }.apply {
                status shouldBe HttpStatusCode.OK
            }
            Thread.sleep(100)
        }
    }

    @Test
    fun `test stale rate limit entries are cleaned up`() = runTest2(
        appConfig = baseConfig.copy(
            rateLimit = RateLimitConfig(limit = 2, resetTime = Duration.ofSeconds(2))
        )
    ) {
        // Exhaust rate limit for an IP
        repeat(2) {
            http.get("/v1/status") {
                header("X-Real-IP", "192.168.1.1")
            }.apply {
                status shouldBe HttpStatusCode.OK
            }
            Thread.sleep(100)
        }

        http.get("/v1/status") {
            header("X-Real-IP", "192.168.1.1")
        }.apply {
            status shouldBe HttpStatusCode.TooManyRequests
        }
        Thread.sleep(100)

        // Wait for entries to become stale (2.5 seconds > resetTime of 2 seconds)
        Thread.sleep(2500)

        // Should be able to make requests again since entries were cleaned up
        repeat(2) {
            http.get("/v1/status") {
                header("X-Real-IP", "192.168.1.1")
            }.apply {
                status shouldBe HttpStatusCode.OK
            }
            Thread.sleep(100)
        }
    }
}
