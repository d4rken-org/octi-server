package eu.darken.octi.server.module

import eu.darken.octi.TestRunner
import eu.darken.octi.createDevice
import eu.darken.octi.writeModule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * End-to-end coverage of the GC quota-sync invariant: when stale modules or stale
 * devices are reaped by background GC, the AccountStorageTracker counter must drop
 * by the corresponding bytes. Pre-G8 fix the counter only reconciled on restart.
 */
class GCQuotaSyncTest : TestRunner() {

    @Test
    fun `module GC adjusts account used bytes when stale module is reaped`() = runTest2(
        appConfig = baseConfig.copy(
            moduleExpiration = Duration.ofSeconds(2),
            moduleGCInterval = Duration.ofSeconds(1),
        ),
    ) {
        val creds = createDevice()
        val tracker = component.storageTracker()
        val accountId = UUID.fromString(creds.account)

        writeModule(creds, "abc", data = "x".repeat(128)).status.value shouldBe 200
        tracker.getUsage(accountId).usedBytes shouldBe 128L

        // Wait for expiration + at least one GC tick.
        Thread.sleep(config.moduleExpiration.toMillis() + 1500)

        tracker.getUsage(accountId).usedBytes shouldBe 0L
    }

    @Test
    fun `device GC routes through lifecycle service and clears account quota`() = runTest2(
        appConfig = baseConfig.copy(
            deviceExpiration = Duration.ofSeconds(2),
            deviceGCInterval = Duration.ofSeconds(1),
        ),
    ) {
        val creds = createDevice()
        val tracker = component.storageTracker()
        val accountId = UUID.fromString(creds.account)

        writeModule(creds, "abc", data = "x".repeat(64)).status.value shouldBe 200
        writeModule(creds, "def", data = "x".repeat(96)).status.value shouldBe 200
        tracker.getUsage(accountId).usedBytes shouldBe (64L + 96L)

        // Wait for device expiration + at least one GC tick.
        Thread.sleep(config.deviceExpiration.toMillis() + 1500)

        tracker.getUsage(accountId).usedBytes shouldBe 0L
    }
}
