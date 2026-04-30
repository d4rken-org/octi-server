package eu.darken.octi.server.ws

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SyncNotificationStatsTest {

    @Test
    fun `empty snapshot produces no report`() {
        val stats = SyncNotificationStats()

        stats.snapshotAndReset() shouldBe null
    }

    @Test
    fun `counters aggregate correctly`() {
        val stats = SyncNotificationStats()

        stats.recordBatch(
            listOf(
                event("eu.darken.octi.module.core.clipboard"),
                event("eu.darken.octi.module.core.power"),
                event("eu.darken.octi.module.core.clipboard"),
            )
        )
        stats.recordDelivery(eventCount = 2)
        stats.recordDelivery(eventCount = 1)
        stats.recordSkippedSelfPeer()
        stats.recordNoPeers()
        stats.recordClosedSession()
        stats.recordBufferFullDrop()
        stats.recordFailure()

        val snapshot = stats.snapshotAndReset()!!

        snapshot shouldBe SyncNotificationStats.Snapshot(
            batches = 1,
            events = 3,
            deliveredPayloads = 2,
            deliveredEvents = 3,
            skippedSelfPeers = 1,
            noPeers = 1,
            closedSessions = 1,
            bufferFullDrops = 1,
            failures = 1,
            moduleCounts = mapOf(
                "eu.darken.octi.module.core.clipboard" to 2L,
                "eu.darken.octi.module.core.power" to 1L,
            ),
        )
    }

    @Test
    fun `snapshot resets counters`() {
        val stats = SyncNotificationStats()

        stats.recordBatch(listOf(event("eu.darken.octi.module.core.clipboard")))

        stats.snapshotAndReset()!!.events shouldBe 1
        stats.snapshotAndReset() shouldBe null
    }

    @Test
    fun `formatted line includes sorted module counts`() {
        val snapshot = SyncNotificationStats.Snapshot(
            batches = 2,
            events = 8,
            deliveredPayloads = 3,
            deliveredEvents = 6,
            skippedSelfPeers = 1,
            noPeers = 1,
            closedSessions = 1,
            bufferFullDrops = 1,
            failures = 0,
            moduleCounts = mapOf(
                "eu.darken.octi.module.core.meta" to 1L,
                "eu.darken.octi.module.core.power" to 2L,
                "eu.darken.octi.module.core.clipboard" to 5L,
            ),
        )

        val line = snapshot.format(Duration.ofMinutes(1))

        line shouldBe """
            sync-stats: 1m
              traffic: batches=2 events=8 deliveredPayloads=3 deliveredEvents=6
              outcomes: skippedSelfPeers=1 noPeers=1 closedSessions=1 bufferFullDrops=1 failures=0
              modules:
                eu.darken.octi.module.core.clipboard=5
                eu.darken.octi.module.core.power=2
                eu.darken.octi.module.core.meta=1
        """.trimIndent()
        line shouldContain "\n    eu.darken.octi.module.core.clipboard=5"
    }

    private fun event(moduleId: String): SyncNotifier.EventPayload.Event.ModuleChanged =
        SyncNotifier.EventPayload.Event.ModuleChanged(
            deviceId = UUID.randomUUID().toString(),
            moduleId = moduleId,
            modifiedAt = Instant.EPOCH.toString(),
            action = "updated",
            sourceDeviceId = UUID.randomUUID().toString(),
        )
}
