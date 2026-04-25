package eu.darken.octi.server.account

import eu.darken.octi.server.App
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.*

class AccountStorageTrackerTest {

    private fun config(quota: Long): App.Config = App.Config(
        port = 0,
        dataPath = Path.of("/tmp/unused-by-tracker-tests"),
        accountQuotaBytes = quota,
    )

    private fun tracker(quota: Long = 1024): AccountStorageTracker =
        AccountStorageTracker(config(quota))

    @Test
    fun `tryReserve succeeds at exact quota boundary`() {
        val t = tracker(quota = 1024)
        val a = UUID.randomUUID()

        t.tryReserve(a, 1024) shouldBe true
        t.tryReserve(a, 1) shouldBe false
    }

    @Test
    fun `release allows reservation again`() {
        val t = tracker(quota = 1024)
        val a = UUID.randomUUID()

        t.tryReserve(a, 1024) shouldBe true
        t.releaseReservation(a, 1024)
        t.tryReserve(a, 1024) shouldBe true
    }

    @Test
    fun `releaseReservation underflow clamps to zero`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.tryReserve(a, 100) shouldBe true
        t.releaseReservation(a, 9999)

        t.getUsage(a).reservedBytes shouldBe 0
    }

    @Test
    fun `commitReservation with orphaned greater than newReferenced keeps used non-negative`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.adjustUsed(a, 500)
        t.commitReservation(a, reservedBytes = 0, orphanedBytes = 9999)

        t.getUsage(a).usedBytes shouldBe 0
    }

    @Test
    fun `commitReservation with reservedBytes greater than current reserved clamps to zero`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.tryReserve(a, 100) shouldBe true
        t.commitReservation(a, reservedBytes = 9999, orphanedBytes = 0)

        val usage = t.getUsage(a)
        usage.reservedBytes shouldBe 0
        usage.usedBytes shouldBeGreaterThanOrEqual 0
    }

    @Test
    fun `adjustUsed with negative delta at zero stays at zero`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.adjustUsed(a, -100)

        t.getUsage(a).usedBytes shouldBe 0
    }

    @Test
    fun `accounts do not cross-contaminate`() {
        val t = tracker(quota = 1024)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        t.tryReserve(a, 1024) shouldBe true
        t.tryReserve(b, 1024) shouldBe true

        t.getUsage(a).reservedBytes shouldBe 1024
        t.getUsage(b).reservedBytes shouldBe 1024

        t.releaseReservation(a, 1024)
        t.getUsage(a).reservedBytes shouldBe 0
        t.getUsage(b).reservedBytes shouldBe 1024
    }

    @Test
    fun `rebuildUsage overwrites prior values idempotently`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.adjustUsed(a, 500)
        t.tryReserve(a, 200) shouldBe true

        t.rebuildUsage(a, usedBytes = 42, reservedBytes = 7)

        val usage = t.getUsage(a)
        usage.usedBytes shouldBe 42
        usage.reservedBytes shouldBe 7
    }

    @Test
    fun `removeAccount drops the tracker entry`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.adjustUsed(a, 500)
        t.tryReserve(a, 200) shouldBe true

        t.removeAccount(a)

        val usage = t.getUsage(a)
        usage.usedBytes shouldBe 0
        usage.reservedBytes shouldBe 0
    }

    // Documents current behaviour for negative input — there is no require(sizeBytes >= 0)
    // guard today. If production adds validation, this test should fail and be updated.
    @Test
    fun `tryReserve with negative size documents current behaviour`() {
        val t = tracker(quota = 100)
        val a = UUID.randomUUID()

        // Negative reserve reduces the requested total, which is trivially below quota.
        // Current implementation accepts this; the reservedBytes field holds the negative delta.
        t.tryReserve(a, -50) shouldBe true
        t.getUsage(a).reservedBytes shouldBe -50
    }

    @Test
    fun `tryAdjustUsed accepts positive delta within quota and rejects past it`() {
        val t = tracker(quota = 1024)
        val a = UUID.randomUUID()

        t.tryAdjustUsed(a, 500) shouldBe true
        t.getUsage(a).usedBytes shouldBe 500

        t.tryAdjustUsed(a, 524) shouldBe true
        t.getUsage(a).usedBytes shouldBe 1024

        t.tryAdjustUsed(a, 1) shouldBe false
        t.getUsage(a).usedBytes shouldBe 1024
    }

    @Test
    fun `tryAdjustUsed counts reservedBytes against quota`() {
        val t = tracker(quota = 1024)
        val a = UUID.randomUUID()

        t.tryReserve(a, 800) shouldBe true
        // 224 bytes free (1024 - 800 reserved); a 300-byte doc must be rejected.
        t.tryAdjustUsed(a, 300) shouldBe false
        t.getUsage(a).usedBytes shouldBe 0
    }

    @Test
    fun `tryAdjustUsed always applies non-positive deltas without quota check`() {
        val t = tracker(quota = 100)
        val a = UUID.randomUUID()

        t.adjustUsed(a, 100)

        // Negative delta — frees space, no check needed.
        t.tryAdjustUsed(a, -40) shouldBe true
        t.getUsage(a).usedBytes shouldBe 60

        // Zero delta — no-op success.
        t.tryAdjustUsed(a, 0) shouldBe true
        t.getUsage(a).usedBytes shouldBe 60
    }

    @Test
    fun `tryAdjustUsed negative delta clamps used to zero`() {
        val t = tracker()
        val a = UUID.randomUUID()

        t.adjustUsed(a, 50)
        t.tryAdjustUsed(a, -9999) shouldBe true
        t.getUsage(a).usedBytes shouldBe 0
    }

    @Test
    fun `concurrent tryReserve up to quota never oversells`() = runBlocking {
        val quota = 10_000L
        val t = tracker(quota = quota)
        val a = UUID.randomUUID()

        val lock = Mutex()
        var granted = 0L

        val workers = (1..32).map {
            async {
                repeat(200) {
                    if (t.tryReserve(a, 10)) {
                        lock.withLock { granted += 10 }
                    }
                }
            }
        }
        workers.awaitAll()

        val usage = t.getUsage(a)
        granted shouldBe usage.reservedBytes
        (usage.totalBytes <= quota) shouldBe true
    }
}
