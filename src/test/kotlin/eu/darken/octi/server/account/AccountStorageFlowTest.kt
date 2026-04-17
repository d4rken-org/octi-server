package eu.darken.octi.server.account

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

class AccountStorageFlowTest : TestRunner() {

    @Serializable
    data class StorageInfo(
        val storageApiVersion: Int = 0,
        val accountQuotaBytes: Long = 0,
        val usedBytes: Long = 0,
        val reservedBytes: Long = 0,
        val availableBytes: Long = 0,
        val maxBlobBytes: Long = 0,
        val maxModuleDocumentBytes: Long = 0,
        val maxActiveUploadSessionsPerDevice: Int = 0,
        val idleSessionTtlSeconds: Long = 0,
        val absoluteSessionTtlSeconds: Long = 0,
    )

    @Test
    fun `storage endpoint requires auth`() = runTest2 {
        http.get("/v1/account/storage").apply {
            status shouldBe HttpStatusCode.BadRequest
        }
    }

    @Test
    fun `storage endpoint returns quota info`() = runTest2 {
        val creds = createDevice()

        http.get("/v1/account/storage") {
            addCredentials(creds)
        }.apply {
            status shouldBe HttpStatusCode.OK
            val storage = body<StorageInfo>()
            storage.storageApiVersion shouldBe 1
            (storage.accountQuotaBytes > 0) shouldBe true
            (storage.maxBlobBytes > 0) shouldBe true
            (storage.maxModuleDocumentBytes > 0) shouldBe true
            (storage.maxActiveUploadSessionsPerDevice > 0) shouldBe true
        }
    }

    @Test
    fun `storage shows zero usage for new account`() = runTest2 {
        val creds = createDevice()

        http.get("/v1/account/storage") {
            addCredentials(creds)
        }.apply {
            val storage = body<StorageInfo>()
            storage.usedBytes shouldBe 0
            storage.reservedBytes shouldBe 0
            storage.availableBytes shouldBe storage.accountQuotaBytes
        }
    }
}
