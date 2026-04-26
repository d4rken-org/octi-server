package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import java.util.*

class ModuleReasonHeaderFlowTest : TestRunner() {

    private val moduleId = "eu.darken.octi.module.reason"

    private fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    @Test
    fun `legacy POST module quota rejection carries account_quota_exceeded reason`() {
        runTest2(
            appConfig = baseConfig.copy(
                minFreeDiskSpaceBytes = 0L,
                accountQuotaBytes = 1024,
            ),
        ) {
            val creds = createDevice()
            // Reserve the full account quota up-front; any subsequent positive delta is rejected.
            component.storageTracker()
                .tryReserve(UUID.fromString(creds.account), 1024) shouldBe true

            http.post("/v1/module/$moduleId") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                contentType(ContentType.Application.OctetStream)
                setBody("X")
            }.apply {
                status shouldBe HttpStatusCode.InsufficientStorage
                headers["X-Octi-Reason"] shouldBe "account_quota_exceeded"
            }
        }
    }

    @Test
    fun `PUT commit module quota rejection carries account_quota_exceeded reason`() {
        runTest2(
            appConfig = baseConfig.copy(
                minFreeDiskSpaceBytes = 0L,
                accountQuotaBytes = 1024,
            ),
        ) {
            val creds = createDevice()
            component.storageTracker()
                .tryReserve(UUID.fromString(creds.account), 1024) shouldBe true

            val doc = "X".toByteArray()
            http.put("/v1/module/$moduleId") {
                url { parameters.append("device-id", creds.deviceId.toString()) }
                addCredentials(creds)
                header("If-None-Match", "*")
                contentType(ContentType.Application.Json)
                setBody("""{"documentBase64": "${base64Encode(doc)}", "blobRefs": []}""")
            }.apply {
                status shouldBe HttpStatusCode.InsufficientStorage
                headers["X-Octi-Reason"] shouldBe "account_quota_exceeded"
            }
        }
    }
}
