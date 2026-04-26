package eu.darken.octi.server.account

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

class ShareRepoTest : TestRunner() {

    @Test
    fun `shares expire`() = runTest2(
        appConfig = baseConfig.copy(
            shareExpiration = Duration.ofSeconds(2),
            shareGCInterval = Duration.ofSeconds(2),
        )
    ) {
        val creds1 = createDevice()
        val share1 = createShareCode(creds1)
        Thread.sleep(config.shareExpiration.toMillis() + 1000)
        createDeviceRaw(shareCode = share1).apply {
            status shouldBe HttpStatusCode.Forbidden
            bodyAsText() shouldBe "Invalid ShareCode"
        }
        val share2 = createShareCode(creds1)
        createDevice(shareCode = share2) shouldNotBe null
    }

    @Test
    fun `shares is saved to disk`() = runTest2 {
        val creds1 = createDevice()
        val shareCode = createShareCode(creds1)
        getSharesPath(creds1).apply {
            exists() shouldBe true
            listDirectoryEntries().first().readText() shouldContain shareCode
        }
    }

    @Test
    fun `shares are restored on reboot`() {
        var creds1: Credentials? = null
        var shareCode: String? = null
        runTest2(keepData = true) {
            creds1 = createDevice()
            shareCode = createShareCode(creds1)
        }
        runTest2 {
            val creds2 = createDevice(shareCode = shareCode!!)
            creds1!!.account shouldBe creds2.account
        }
    }

    @Test
    fun `shares consumption deletes the file`() = runTest2 {
        val creds1 = createDevice()
        val share1 = createShareCode(creds1)
        createDevice(shareCode = share1)
        getSharesPath(creds1).listDirectoryEntries().isEmpty() shouldBe true
    }

    @Test
    fun `share code can only be consumed once under concurrent registration`() = runTest2 {
        val owner = createDevice()
        val shareCode = createShareCode(owner)

        val responses = coroutineScope {
            (1..16).map {
                async {
                    createDeviceRaw(deviceId = UUID.randomUUID(), shareCode = shareCode)
                }
            }.awaitAll()
        }

        responses.count { it.status == HttpStatusCode.OK } shouldBe 1
        responses.count { it.status == HttpStatusCode.Forbidden } shouldBe 15
        getDevices(owner).devices.size shouldBe 2
        getSharesPath(owner).listDirectoryEntries().isEmpty() shouldBe true
    }
}
