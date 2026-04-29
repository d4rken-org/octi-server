package eu.darken.octi.server.device

import eu.darken.octi.TestRunner
import eu.darken.octi.createDevice
import eu.darken.octi.getDevices
import eu.darken.octi.getDevicesRaw
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.exists

class DeviceRepoTest : TestRunner() {

    @Test
    fun `old devices are deleted`() = runTest2(
        appConfig = baseConfig.copy(
            deviceExpiration = Duration.ofSeconds(2),
            deviceGCInterval = Duration.ofSeconds(1),
        ),
    ) {
        val creds1 = createDevice()
        getDevices(creds1) shouldNotBe null
        Thread.sleep(config.deviceExpiration.toMillis() - 100)
        getDevices(creds1) shouldNotBe null
        Thread.sleep(config.deviceExpiration.toMillis() + 1000)
        getDevicesRaw(creds1).apply {
            status shouldBe HttpStatusCode.NotFound
            bodyAsText() shouldStartWith "Unknown device"
        }
    }

    @Test
    fun `concurrent updateDevice cannot resurrect deleted device`() = runTest2 {
        val creds = createDevice()
        val accountId = UUID.fromString(creds.account)
        val key = DeviceKey(accountId, creds.deviceId)

        val updateStarted = CountDownLatch(1)
        val allowUpdateToFinish = CountDownLatch(1)
        val updateError = AtomicReference<Throwable?>()
        val deleteError = AtomicReference<Throwable?>()

        val updateThread = thread(name = "device-update-race") {
            try {
                runBlocking {
                    component.deviceRepo().updateDevice(key) { data ->
                        updateStarted.countDown()
                        if (!allowUpdateToFinish.await(5, TimeUnit.SECONDS)) {
                            error("Timed out waiting to finish update")
                        }
                        data.copy(label = "updated")
                    }
                }
            } catch (t: Throwable) {
                updateError.set(t)
            }
        }

        updateStarted.awaitOrFail("update did not start")

        val deleteThread = thread(name = "device-delete-race") {
            try {
                runBlocking {
                    component.deviceRepo().deleteDevice(key)
                }
            } catch (t: Throwable) {
                deleteError.set(t)
            }
        }

        Thread.sleep(100)
        allowUpdateToFinish.countDown()
        updateThread.joinOrFail(updateError)
        deleteThread.joinOrFail(deleteError)

        component.deviceRepo().getDevice(key) shouldBe null
        getDevicePath(creds).exists() shouldBe false
    }

    @Test
    fun `concurrent updateDevice cannot resurrect bulk-deleted device`() = runTest2 {
        val creds = createDevice()
        val accountId = UUID.fromString(creds.account)
        val key = DeviceKey(accountId, creds.deviceId)

        val updateStarted = CountDownLatch(1)
        val allowUpdateToFinish = CountDownLatch(1)
        val updateError = AtomicReference<Throwable?>()
        val deleteError = AtomicReference<Throwable?>()

        val updateThread = thread(name = "device-update-bulk-race") {
            try {
                runBlocking {
                    component.deviceRepo().updateDevice(key) { data ->
                        updateStarted.countDown()
                        if (!allowUpdateToFinish.await(5, TimeUnit.SECONDS)) {
                            error("Timed out waiting to finish update")
                        }
                        data.copy(label = "updated")
                    }
                }
            } catch (t: Throwable) {
                updateError.set(t)
            }
        }

        updateStarted.awaitOrFail("update did not start")

        val deleteThread = thread(name = "device-delete-bulk-race") {
            try {
                runBlocking {
                    component.deviceRepo().deleteDevices(accountId)
                }
            } catch (t: Throwable) {
                deleteError.set(t)
            }
        }

        Thread.sleep(100)
        allowUpdateToFinish.countDown()
        updateThread.joinOrFail(updateError)
        deleteThread.joinOrFail(deleteError)

        component.deviceRepo().getDevice(key) shouldBe null
        getDevicePath(creds).exists() shouldBe false
    }

    @Test
    fun `blocked updateDevice for one device does not block unrelated device updates`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val accountId = UUID.fromString(creds1.account)
        val key1 = DeviceKey(accountId, creds1.deviceId)
        val key2 = DeviceKey(accountId, creds2.deviceId)
        val repo = component.deviceRepo()
        val device1 = repo.getDevice(key1)!!

        val blockedUpdateError = AtomicReference<Throwable?>()
        val otherUpdateError = AtomicReference<Throwable?>()
        var otherUpdateThread: Thread? = null
        var otherUpdateCompletedPromptly = false

        device1.sync.lock()
        val blockedUpdateThread = thread(name = "device-update-blocked-on-sync") {
            try {
                runBlocking {
                    repo.updateDevice(key1) { data ->
                        data.copy(label = "blocked update finished")
                    }
                }
            } catch (t: Throwable) {
                blockedUpdateError.set(t)
            }
        }

        try {
            Thread.sleep(250)
            otherUpdateThread = thread(name = "device-update-unrelated") {
                try {
                    runBlocking {
                        repo.updateDevice(key2) { data ->
                            data.copy(label = "unrelated update finished")
                        }
                    }
                } catch (t: Throwable) {
                    otherUpdateError.set(t)
                }
            }
            otherUpdateThread.join(1_000)
            otherUpdateCompletedPromptly = !otherUpdateThread.isAlive
        } finally {
            device1.sync.unlock()
        }

        blockedUpdateThread.joinOrFail(blockedUpdateError)
        otherUpdateThread.joinOrFail(otherUpdateError)
        otherUpdateCompletedPromptly shouldBe true
        repo.getDevice(key1)!!.label shouldBe "blocked update finished"
        repo.getDevice(key2)!!.label shouldBe "unrelated update finished"
    }

    @Test
    fun `deleteDevice waiting on one device sync does not block unrelated device updates`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val accountId = UUID.fromString(creds1.account)
        val key1 = DeviceKey(accountId, creds1.deviceId)
        val key2 = DeviceKey(accountId, creds2.deviceId)
        val repo = component.deviceRepo()
        val device1 = repo.getDevice(key1)!!

        val deleteError = AtomicReference<Throwable?>()
        val otherUpdateError = AtomicReference<Throwable?>()
        var otherUpdateThread: Thread? = null
        var otherUpdateCompletedPromptly = false

        device1.sync.lock()
        val deleteThread = thread(name = "device-delete-blocked-on-sync") {
            try {
                runBlocking {
                    repo.deleteDevice(key1)
                }
            } catch (t: Throwable) {
                deleteError.set(t)
            }
        }

        try {
            waitUntilOrFail("delete did not remove the device from memory") {
                runBlocking { repo.getDevice(key1) == null }
            }
            otherUpdateThread = thread(name = "device-update-while-delete-blocked") {
                try {
                    runBlocking {
                        repo.updateDevice(key2) { data ->
                            data.copy(label = "updated while delete blocked")
                        }
                    }
                } catch (t: Throwable) {
                    otherUpdateError.set(t)
                }
            }
            otherUpdateThread.join(1_000)
            otherUpdateCompletedPromptly = !otherUpdateThread.isAlive
        } finally {
            device1.sync.unlock()
        }

        deleteThread.joinOrFail(deleteError)
        otherUpdateThread.joinOrFail(otherUpdateError)
        otherUpdateCompletedPromptly shouldBe true
        repo.getDevice(key1) shouldBe null
        repo.getDevice(key2)!!.label shouldBe "updated while delete blocked"
        getDevicePath(creds1).exists() shouldBe false
    }

    private fun CountDownLatch.awaitOrFail(message: String) {
        if (!await(5, TimeUnit.SECONDS)) throw AssertionError(message)
    }

    private fun waitUntilOrFail(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError(message)
            Thread.sleep(10)
        }
    }

    private fun Thread.joinOrFail(error: AtomicReference<Throwable?>) {
        join(5_000)
        isAlive shouldBe false
        error.get()?.let { throw AssertionError("Thread failed", it) }
    }
}
