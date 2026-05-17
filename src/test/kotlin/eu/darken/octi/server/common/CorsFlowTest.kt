package eu.darken.octi.server.common

import eu.darken.octi.TestRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test

class CorsFlowTest : TestRunner() {

    private val allowedOrigin = "http://localhost:5173"
    private val disallowedOrigin = "http://evil.example"

    private val corsConfig = baseConfig.copy(corsAllowedOrigins = setOf(allowedOrigin))
    private val corsDisabledConfig = baseConfig.copy(corsAllowedOrigins = emptySet())

    @Test
    fun `when explicitly disabled - no CORS headers emitted`() = runTest2(appConfig = corsDisabledConfig) {
        http.get("/v1/status") {
            header(HttpHeaders.Origin, allowedOrigin)
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
        }
    }

    @Test
    fun `when explicitly disabled - preflight has no Allow-Origin`() = runTest2(appConfig = corsDisabledConfig) {
        // Without the CORS plugin installed, Ktor doesn't synthesize a preflight response.
        // The actual status code is uninteresting (some routes 404 OPTIONS, some 405); what
        // matters is that no Allow-Origin header is emitted — which is what blocks the browser.
        val response = http.options("/v1/devices") {
            header(HttpHeaders.Origin, allowedOrigin)
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
            header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.Authorization)
        }
        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
    }

    @Test
    fun `default config allows the official octi-web origin out of the box`() = runTest2 {
        // baseConfig inherits Config defaults, which include https://web.octi.darken.eu —
        // this guards the OOB UX promise: docker run + no flags = SPA works.
        http.get("/v1/status") {
            header(HttpHeaders.Origin, "https://web.octi.darken.eu")
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://web.octi.darken.eu"
        }
    }

    @Test
    fun `default config allows the d4rken github pages origin`() = runTest2 {
        http.get("/v1/status") {
            header(HttpHeaders.Origin, "https://d4rken.github.io")
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers[HttpHeaders.AccessControlAllowOrigin] shouldBe "https://d4rken.github.io"
        }
    }

    @Test
    fun `preflight from allowed origin returns CORS headers`() = runTest2(appConfig = corsConfig) {
        val response = http.options("/v1/devices") {
            header(HttpHeaders.Origin, allowedOrigin)
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
            header(HttpHeaders.AccessControlRequestHeaders, "${HttpHeaders.Authorization},X-Device-ID")
        }
        // Ktor's CORS plugin responds 200 OK with allow-origin echoed
        response.status shouldBe HttpStatusCode.OK
        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe allowedOrigin
    }

    @Test
    fun `preflight from disallowed origin gets no Allow-Origin`() = runTest2(appConfig = corsConfig) {
        val response = http.options("/v1/devices") {
            header(HttpHeaders.Origin, disallowedOrigin)
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }
        // Browser refuses the request because the header is missing — server itself doesn't reject loudly
        response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
    }

    @Test
    fun `actual GET from allowed origin includes Allow-Origin header`() = runTest2(appConfig = corsConfig) {
        http.get("/v1/status") {
            header(HttpHeaders.Origin, allowedOrigin)
        }.apply {
            status shouldBe HttpStatusCode.OK
            headers[HttpHeaders.AccessControlAllowOrigin] shouldBe allowedOrigin
        }
    }

    @Test
    fun `actual GET from disallowed origin is rejected with 403`() = runTest2(appConfig = corsConfig) {
        // Ktor's CORS plugin rejects unlisted origins server-side (not just by withholding the
        // Allow-Origin header). Browsers would refuse the response anyway, but failing fast
        // also avoids leaking server state to a curl from a disallowed origin.
        http.get("/v1/status") {
            header(HttpHeaders.Origin, disallowedOrigin)
        }.apply {
            status shouldBe HttpStatusCode.Forbidden
            headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
        }
    }

    @Test
    fun `actual response exposes blob and upload headers to the browser`() = runTest2(appConfig = corsConfig) {
        // Without Access-Control-Expose-Headers, fetch() in the SPA can't read these even
        // though the server sends them — so we verify the configured exposeHeader set actually
        // lands on a real (non-preflight) response.
        http.get("/v1/status") {
            header(HttpHeaders.Origin, allowedOrigin)
        }.apply {
            status shouldBe HttpStatusCode.OK
            val exposed = headers[HttpHeaders.AccessControlExposeHeaders]?.lowercase() ?: ""
            // Last-Modified is a CORS-safelisted response header — browsers expose it by default
            // so Ktor's plugin (correctly) omits it from Access-Control-Expose-Headers even
            // when we configure it. Not asserted here for that reason.
            val expected = listOf(
                "etag",
                "content-range",
                "accept-ranges",
                "retry-after",
                "upload-offset",
                "upload-length",
                "upload-expires",
                "upload-state",
                "x-blob-id",
            )
            withClue("Access-Control-Expose-Headers='$exposed' missing: ${expected.filterNot { it in exposed }}") {
                expected.forEach { (it in exposed) shouldBe true }
            }
        }
    }

    @Test
    fun `preflight exposes the auth and device headers we need`() = runTest2(appConfig = corsConfig) {
        val response = http.options("/v1/account") {
            header(HttpHeaders.Origin, allowedOrigin)
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            header(HttpHeaders.AccessControlRequestHeaders, "${HttpHeaders.Authorization},X-Device-ID,Octi-Device-Platform,Octi-Device-Label")
        }
        response.status shouldBe HttpStatusCode.OK
        val allowed = response.headers[HttpHeaders.AccessControlAllowHeaders]?.lowercase() ?: ""
        // Ktor folds requested headers into the response — assert each one we plan to send
        listOf("authorization", "x-device-id", "octi-device-platform", "octi-device-label").forEach {
            (it in allowed) shouldBe true
        }
    }
}
