package eu.darken.octi.server.ws

import java.time.Duration

internal class SyncNotificationStats {

    data class Snapshot(
        val batches: Long,
        val events: Long,
        val deliveredPayloads: Long,
        val deliveredEvents: Long,
        val skippedSelfPeers: Long,
        val noPeers: Long,
        val closedSessions: Long,
        val bufferFullDrops: Long,
        val failures: Long,
        val moduleCounts: Map<String, Long>,
    ) {
        fun format(window: Duration): String {
            val modules = moduleCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })

            return buildString {
                appendLine("sync-stats: ${window.toCompactString()}")
                appendLine(
                    "  traffic: batches=$batches events=$events " +
                        "deliveredPayloads=$deliveredPayloads deliveredEvents=$deliveredEvents"
                )
                appendLine(
                    "  outcomes: skippedSelfPeers=$skippedSelfPeers noPeers=$noPeers " +
                        "closedSessions=$closedSessions bufferFullDrops=$bufferFullDrops failures=$failures"
                )
                appendLine("  modules:")
                if (modules.isEmpty()) {
                    appendLine("    <none>")
                } else {
                    modules.forEach { appendLine("    ${it.key}=${it.value}") }
                }
            }.trimEnd()
        }
    }

    private val lock = Any()
    private var batches = 0L
    private var events = 0L
    private var deliveredPayloads = 0L
    private var deliveredEvents = 0L
    private var skippedSelfPeers = 0L
    private var noPeers = 0L
    private var closedSessions = 0L
    private var bufferFullDrops = 0L
    private var failures = 0L
    private val moduleCounts = mutableMapOf<String, Long>()

    fun recordBatch(events: List<SyncNotifier.EventPayload.Event>) = synchronized(lock) {
        batches += 1
        this.events += events.size.toLong()
        events.forEach { event ->
            when (event) {
                is SyncNotifier.EventPayload.Event.ModuleChanged -> {
                    moduleCounts[event.moduleId] = (moduleCounts[event.moduleId] ?: 0L) + 1L
                }
            }
        }
    }

    fun recordDelivery(eventCount: Int) = synchronized(lock) {
        deliveredPayloads += 1
        deliveredEvents += eventCount.toLong()
    }

    fun recordSkippedSelfPeer() = synchronized(lock) {
        skippedSelfPeers += 1
    }

    fun recordNoPeers() = synchronized(lock) {
        noPeers += 1
    }

    fun recordClosedSession() = synchronized(lock) {
        closedSessions += 1
    }

    fun recordBufferFullDrop() = synchronized(lock) {
        bufferFullDrops += 1
    }

    fun recordFailure() = synchronized(lock) {
        failures += 1
    }

    fun snapshotAndReset(): Snapshot? = synchronized(lock) {
        if (isEmpty()) return@synchronized null

        Snapshot(
            batches = batches,
            events = events,
            deliveredPayloads = deliveredPayloads,
            deliveredEvents = deliveredEvents,
            skippedSelfPeers = skippedSelfPeers,
            noPeers = noPeers,
            closedSessions = closedSessions,
            bufferFullDrops = bufferFullDrops,
            failures = failures,
            moduleCounts = moduleCounts.toMap(),
        ).also {
            reset()
        }
    }

    private fun isEmpty(): Boolean =
        batches == 0L &&
            events == 0L &&
            deliveredPayloads == 0L &&
            deliveredEvents == 0L &&
            skippedSelfPeers == 0L &&
            noPeers == 0L &&
            closedSessions == 0L &&
            bufferFullDrops == 0L &&
            failures == 0L &&
            moduleCounts.isEmpty()

    private fun reset() {
        batches = 0L
        events = 0L
        deliveredPayloads = 0L
        deliveredEvents = 0L
        skippedSelfPeers = 0L
        noPeers = 0L
        closedSessions = 0L
        bufferFullDrops = 0L
        failures = 0L
        moduleCounts.clear()
    }
}

private fun Duration.toCompactString(): String {
    val seconds = toSeconds()
    return when {
        seconds % 3600L == 0L -> "${seconds / 3600L}h"
        seconds % 60L == 0L -> "${seconds / 60L}m"
        else -> "${seconds}s"
    }
}
