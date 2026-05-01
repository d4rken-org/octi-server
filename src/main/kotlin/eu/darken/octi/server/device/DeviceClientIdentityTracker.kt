package eu.darken.octi.server.device

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory client-identity tracker used for the device-activity log.
 * Stores the last User-Agent and `seenAt` per device for successful auth, and a capped
 * stream of failed-auth events. Not authoritative — wiped on restart.
 */
@Singleton
class DeviceClientIdentityTracker @Inject constructor() {

    data class TrackedActivity(
        val userAgent: String,
        val seenAt: Instant,
    )

    data class AuthFailureEvent(
        val seenAt: Instant,
        val reasonTag: String,
        val userAgent: String,
    )

    private val activity = ConcurrentHashMap<DeviceKey, TrackedActivity>()

    // Cap eviction is FIFO by insertion order between snapshots — see snapshotAuthFailures
    // for age-based pruning. The synchronized block keeps insert + cap-trim and the snapshot
    // prune atomic w.r.t. each other; ArrayDeque gives O(1) size/addLast/removeFirst, so the
    // hot-path traversal cost of ConcurrentLinkedDeque.size is gone.
    private val authFailuresLock = Any()
    private val authFailures = ArrayDeque<AuthFailureEvent>()

    fun recordUserAgent(key: DeviceKey, rawUserAgent: String?, seenAt: Instant = Instant.now()) {
        val normalized = normalizeOctiUserAgent(rawUserAgent)
        activity.compute(key) { _, existing ->
            val ua = normalized ?: existing?.userAgent ?: ""
            TrackedActivity(userAgent = ua, seenAt = seenAt)
        }
    }

    fun userAgentFor(key: DeviceKey): String? = activity[key]?.userAgent?.takeIf { it.isNotEmpty() }

    fun retainDevices(keys: Set<DeviceKey>) {
        activity.keys.retainAll(keys)
    }

    fun snapshotActivity(): Map<DeviceKey, TrackedActivity> = activity.toMap()

    fun recordAuthFailure(reasonTag: String, rawUserAgent: String?, seenAt: Instant = Instant.now()) {
        val event = AuthFailureEvent(
            seenAt = seenAt,
            reasonTag = reasonTag,
            userAgent = sanitizeUserAgent(rawUserAgent) ?: "",
        )
        synchronized(authFailuresLock) {
            authFailures.addLast(event)
            while (authFailures.size > MAX_FAILURE_EVENTS) {
                authFailures.removeFirst()
            }
        }
    }

    fun snapshotAuthFailures(now: Instant = Instant.now()): List<AuthFailureEvent> {
        val cutoff = now.minus(FAILURE_RETENTION)
        return synchronized(authFailuresLock) {
            authFailures.removeAll { it.seenAt.isBefore(cutoff) }
            authFailures.toList()
        }
    }

    companion object {
        internal const val MAX_FAILURE_EVENTS = 10_000
        private val FAILURE_RETENTION: Duration = Duration.ofHours(24)
        private const val MAX_USER_AGENT_LENGTH = 256
        private val CONTROL_CHARS = Regex("\\p{Cntrl}+")

        private fun sanitizeUserAgent(raw: String?): String? {
            return raw
                ?.replace(CONTROL_CHARS, " ")
                ?.trim()
                ?.ifBlank { null }
                ?.take(MAX_USER_AGENT_LENGTH)
        }

        private fun normalizeOctiUserAgent(raw: String?): String? {
            return sanitizeUserAgent(raw)?.takeIf { it.startsWith("octi/", ignoreCase = true) }
        }
    }
}
