package eu.darken.octi.server.device

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DeviceClientIdentityTrackerTest {

    private val key = DeviceKey(UUID.randomUUID(), UUID.randomUUID())
    private val now: Instant = Instant.parse("2026-04-29T12:00:00Z")

    @Test
    fun `octi user agent is recorded with seenAt`() {
        val tracker = DeviceClientIdentityTracker()
        val userAgent = "octi/0.11.2-rc0/GPLAY"

        tracker.recordUserAgent(key, userAgent, seenAt = now)

        tracker.userAgentFor(key) shouldBe userAgent
        tracker.snapshotActivity()[key] shouldBe DeviceClientIdentityTracker.TrackedActivity(
            userAgent = userAgent,
            seenAt = now,
        )
    }

    @Test
    fun `non-octi user agent records empty marker but tracks activity`() {
        val tracker = DeviceClientIdentityTracker()

        tracker.recordUserAgent(key, "modded-client/1.0/FOSS", seenAt = now)

        tracker.userAgentFor(key) shouldBe null
        tracker.snapshotActivity()[key] shouldBe DeviceClientIdentityTracker.TrackedActivity(
            userAgent = "",
            seenAt = now,
        )
    }

    @Test
    fun `non-octi update preserves previous octi user agent but bumps seenAt`() {
        val tracker = DeviceClientIdentityTracker()
        val initialAt = now
        val later = now.plus(Duration.ofMinutes(10))

        tracker.recordUserAgent(key, "octi/0.13.0/FOSS", seenAt = initialAt)
        tracker.recordUserAgent(key, "ktor-client", seenAt = later)

        tracker.userAgentFor(key) shouldBe "octi/0.13.0/FOSS"
        tracker.snapshotActivity()[key] shouldBe DeviceClientIdentityTracker.TrackedActivity(
            userAgent = "octi/0.13.0/FOSS",
            seenAt = later,
        )
    }

    @Test
    fun `octi update overwrites previous user agent and bumps seenAt`() {
        val tracker = DeviceClientIdentityTracker()
        val initialAt = now
        val later = now.plus(Duration.ofMinutes(10))

        tracker.recordUserAgent(key, "octi/0.13.0/FOSS", seenAt = initialAt)
        tracker.recordUserAgent(key, "octi/0.14.0/GPLAY", seenAt = later)

        tracker.snapshotActivity()[key] shouldBe DeviceClientIdentityTracker.TrackedActivity(
            userAgent = "octi/0.14.0/GPLAY",
            seenAt = later,
        )
    }

    @Test
    fun `retaining known devices prunes stale identities`() {
        val tracker = DeviceClientIdentityTracker()
        val staleKey = DeviceKey(UUID.randomUUID(), UUID.randomUUID())

        tracker.recordUserAgent(key, "octi/0.13.0-rc0/FOSS", seenAt = now)
        tracker.recordUserAgent(staleKey, "octi/0.16.0-rc0/GPLAY", seenAt = now)
        tracker.retainDevices(setOf(key))

        tracker.snapshotActivity().keys shouldBe setOf(key)
    }

    @Test
    fun `auth failure is recorded with reason and user agent`() {
        val tracker = DeviceClientIdentityTracker()

        tracker.recordAuthFailure("bad-credentials", "octi/1.0.0/FOSS", seenAt = now)

        val snapshot = tracker.snapshotAuthFailures(now)
        snapshot.size shouldBe 1
        snapshot[0] shouldBe DeviceClientIdentityTracker.AuthFailureEvent(
            seenAt = now,
            reasonTag = "bad-credentials",
            userAgent = "octi/1.0.0/FOSS",
            source = AUTH_FAILURE_SOURCE_HTTP,
        )
    }

    @Test
    fun `auth failure source is recorded`() {
        val tracker = DeviceClientIdentityTracker()

        tracker.recordAuthFailure(
            reasonTag = "unknown-device",
            rawUserAgent = "octi/1.0.0/FOSS",
            seenAt = now,
            source = AUTH_FAILURE_SOURCE_WS,
        )

        tracker.snapshotAuthFailures(now)[0].source shouldBe AUTH_FAILURE_SOURCE_WS
    }

    @Test
    fun `auth failure with non-octi user agent is recorded verbatim`() {
        val tracker = DeviceClientIdentityTracker()

        tracker.recordAuthFailure("missing-credentials", "ktor-client", seenAt = now)

        tracker.snapshotAuthFailures(now)[0].userAgent shouldBe "ktor-client"
    }

    @Test
    fun `auth failure with blank user agent records empty marker`() {
        val tracker = DeviceClientIdentityTracker()

        tracker.recordAuthFailure("missing-device-id", null, seenAt = now)

        tracker.snapshotAuthFailures(now)[0].userAgent shouldBe ""
    }

    @Test
    fun `auth failures older than 24h are lazily pruned on snapshot`() {
        val tracker = DeviceClientIdentityTracker()
        val tooOld = now.minus(Duration.ofHours(25))
        val recent = now.minus(Duration.ofHours(1))

        tracker.recordAuthFailure("bad-credentials", "octi/1.0.0/FOSS", seenAt = tooOld)
        tracker.recordAuthFailure("bad-credentials", "octi/1.0.0/FOSS", seenAt = recent)

        val snapshot = tracker.snapshotAuthFailures(now)
        snapshot.size shouldBe 1
        snapshot[0].seenAt shouldBe recent
    }

    @Test
    fun `out-of-order stale events are pruned from the middle on snapshot`() {
        val tracker = DeviceClientIdentityTracker()

        tracker.recordAuthFailure("bad-credentials", "recent-1", seenAt = now.minus(Duration.ofHours(1)))
        tracker.recordAuthFailure("bad-credentials", "stale", seenAt = now.minus(Duration.ofHours(25)))
        tracker.recordAuthFailure("bad-credentials", "recent-2", seenAt = now.minus(Duration.ofMinutes(5)))

        val snapshot = tracker.snapshotAuthFailures(now)
        snapshot.map { it.userAgent } shouldBe listOf("recent-1", "recent-2")
    }

    @Test
    fun `auth failure deque caps at MAX_FAILURE_EVENTS`() {
        val tracker = DeviceClientIdentityTracker()
        val cap = DeviceClientIdentityTracker.MAX_FAILURE_EVENTS

        repeat(cap + 5) { i ->
            tracker.recordAuthFailure("bad-credentials", "ua-$i", seenAt = now.plusMillis(i.toLong()))
        }

        val snapshot = tracker.snapshotAuthFailures(now.plusMillis((cap + 5).toLong()))
        snapshot.size shouldBe cap
        snapshot.first().userAgent shouldBe "ua-5"
        snapshot.last().userAgent shouldBe "ua-${cap + 4}"
    }

    @Test
    fun `concurrent auth failure inserts respect cap and produce a clean snapshot`() {
        val tracker = DeviceClientIdentityTracker()
        val threadCount = 8
        val perThread = 2_000
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)

        try {
            val tasks = (0 until threadCount).map { t ->
                java.util.concurrent.Callable {
                    repeat(perThread) { i ->
                        tracker.recordAuthFailure(
                            reasonTag = "bad-credentials",
                            rawUserAgent = "ua-$t-$i",
                            seenAt = now.plusMillis((t * perThread + i).toLong()),
                        )
                    }
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        val snapshot = tracker.snapshotAuthFailures(now.plusMillis((threadCount * perThread).toLong()))
        (snapshot.size <= DeviceClientIdentityTracker.MAX_FAILURE_EVENTS) shouldBe true
        snapshot.all { it.reasonTag == "bad-credentials" } shouldBe true
    }
}
