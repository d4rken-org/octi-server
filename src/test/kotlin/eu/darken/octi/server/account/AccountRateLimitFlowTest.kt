package eu.darken.octi.server.account

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test

/**
 * Verifies the per-account rate limit gate (G4 / P5.2). The gate runs after
 * credential validation, so over-limit calls return 429 *without* updating
 * device metadata.
 */
class AccountRateLimitFlowTest : TestRunner() {

    @Test
    fun `over-limit authenticated request returns 429`() = runTest2(
        appConfig = baseConfig.copy(
            accountRateLimit = 2,
            accountRateLimitWindowSeconds = 60,
        ),
    ) {
        // POST /v1/account doesn't go through verifyCaller, so createDevice doesn't burn
        // a token. The first auth'd hit per account is /v1/account/storage below.
        val creds = createDevice()

        http.get("/v1/account/storage") { addCredentials(creds) }.status shouldBe HttpStatusCode.OK
        http.get("/v1/account/storage") { addCredentials(creds) }.status shouldBe HttpStatusCode.OK
        http.get("/v1/account/storage") { addCredentials(creds) }.status shouldBe HttpStatusCode.TooManyRequests
    }

    @Test
    fun `unrelated accounts have independent buckets`() = runTest2(
        appConfig = baseConfig.copy(
            accountRateLimit = 1,
            accountRateLimitWindowSeconds = 60,
        ),
    ) {
        val a = createDevice()
        val b = createDevice() // different account

        // a uses its single token.
        http.get("/v1/account/storage") { addCredentials(a) }.status shouldBe HttpStatusCode.OK
        http.get("/v1/account/storage") { addCredentials(a) }.status shouldBe HttpStatusCode.TooManyRequests
        // b's bucket is independent.
        http.get("/v1/account/storage") { addCredentials(b) }.status shouldBe HttpStatusCode.OK
    }
}
