package eu.darken.octi.server.account

import eu.darken.octi.*
import eu.darken.octi.server.device.DeviceKey
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import java.util.UUID

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
    fun `over-limit authenticated request still records client identity`() = runTest2(
        appConfig = baseConfig.copy(
            accountRateLimit = 1,
            accountRateLimitWindowSeconds = 60,
        ),
    ) {
        val firstUserAgent = "octi/1.0.0/FOSS"
        val overLimitUserAgent = "octi/2.0.0/GPLAY"
        val creds = createDevice()
        val key = DeviceKey(UUID.fromString(creds.account), creds.deviceId)

        http.get("/v1/account/storage") {
            addCredentials(creds)
            headers.set(HttpHeaders.UserAgent, firstUserAgent)
        }.status shouldBe HttpStatusCode.OK

        http.get("/v1/account/storage") {
            addCredentials(creds)
            headers.set(HttpHeaders.UserAgent, overLimitUserAgent)
        }.status shouldBe HttpStatusCode.TooManyRequests

        component.deviceClientIdentityTracker().userAgentFor(key) shouldBe overLimitUserAgent
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

    @Test
    fun `accountRateLimit of 0 disables the gate`() = runTest2(
        appConfig = baseConfig.copy(
            accountRateLimit = 0,
            accountRateLimitWindowSeconds = 60,
        ),
    ) {
        // --disable-rate-limits sets accountRateLimit = 0. Verify the per-account gate is
        // genuinely off — not just permissive — by issuing more requests than any normal
        // limit would allow.
        val creds = createDevice()
        repeat(50) {
            http.get("/v1/account/storage") { addCredentials(creds) }.status shouldBe HttpStatusCode.OK
        }
    }
}
