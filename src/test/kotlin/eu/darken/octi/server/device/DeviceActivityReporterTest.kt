package eu.darken.octi.server.device

import eu.darken.octi.server.device.DeviceClientIdentityTracker.AuthFailureEvent
import eu.darken.octi.server.device.DeviceClientIdentityTracker.TrackedActivity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DeviceActivityReporterTest {

    private val now: Instant = Instant.parse("2026-04-29T12:00:00Z")

    @Test
    fun `report buckets activity by seenAt windows`() {
        val activity = mapOf(
            randomKey() to TrackedActivity("octi/1.0.0/FOSS", seenAt = now.minus(Duration.ofMinutes(10))),
            randomKey() to TrackedActivity("octi/2.0.0/GPLAY", seenAt = now.minus(Duration.ofHours(2))),
            randomKey() to TrackedActivity("octi/3.0.0/GPLAY", seenAt = now.minus(Duration.ofHours(30))),
        )

        val report = DeviceActivityReporter.buildReport(activity = activity, now = now)

        report.oneHour.total shouldBe 1
        report.oneHour.versionCounts() shouldBe mapOf("1.0.0" to 1)
        report.oneHour.buildFlavorCounts() shouldBe mapOf("FOSS" to 1)

        report.twentyFourHours.total shouldBe 2
        report.twentyFourHours.versionCounts() shouldBe mapOf(
            "1.0.0" to 1,
            "2.0.0" to 1,
        )
        report.twentyFourHours.buildFlavorCounts() shouldBe mapOf(
            "FOSS" to 1,
            "GPLAY" to 1,
        )
    }

    @Test
    fun `report formats counts and percentages sorted by count`() {
        val activity = mapOf(
            randomKey() to TrackedActivity("octi/1.0.0/FOSS", seenAt = now),
            randomKey() to TrackedActivity("octi/1.0.0/FOSS", seenAt = now),
            randomKey() to TrackedActivity("octi/2.0.0/GPLAY", seenAt = now),
        )

        val report = DeviceActivityReporter.buildReport(activity = activity, now = now)

        DeviceActivityReporter.formatReport(report) shouldBe
            """
            device-stats:
              1h: total=3
                flavors:
                  FOSS=2 (66.7%)
                  GPLAY=1 (33.3%)
                versions:
                  1.0.0=2 (66.7%)
                  2.0.0=1 (33.3%)
                auth-failures: total=0
                  <none>
              24h: total=3
                flavors:
                  FOSS=2 (66.7%)
                  GPLAY=1 (33.3%)
                versions:
                  1.0.0=2 (66.7%)
                  2.0.0=1 (33.3%)
                auth-failures: total=0
                  <none>
            """.trimIndent()
    }

    @Test
    fun `empty activity yields empty windows`() {
        val report = DeviceActivityReporter.buildReport(activity = emptyMap(), now = now)

        report.oneHour.total shouldBe 0
        report.oneHour.versions shouldBe emptyList()
        report.oneHour.buildFlavors shouldBe emptyList()
    }

    @Test
    fun `non-octi activity buckets as unknown for both version and flavor`() {
        val activity = mapOf(
            randomKey() to TrackedActivity("", seenAt = now),
            randomKey() to TrackedActivity("", seenAt = now),
        )

        val report = DeviceActivityReporter.buildReport(activity = activity, now = now)

        report.oneHour.versions shouldBe listOf(
            DeviceActivityReporter.VersionStats(
                version = "<unknown>",
                count = 2,
                percent = 100.0,
            )
        )
        report.oneHour.buildFlavors shouldBe listOf(
            DeviceActivityReporter.BuildFlavorStats(
                flavor = "<unknown>",
                count = 2,
                percent = 100.0,
            )
        )
    }

    @Test
    fun `equivalent octi user agents are grouped together`() {
        val activity = mapOf(
            randomKey() to TrackedActivity("octi/1.0.0/FOSS", seenAt = now),
            randomKey() to TrackedActivity("octi/1.0.0/FOSS", seenAt = now),
            randomKey() to TrackedActivity("", seenAt = now),
            randomKey() to TrackedActivity("", seenAt = now),
        )

        val report = DeviceActivityReporter.buildReport(activity = activity, now = now)

        report.oneHour.versions shouldBe listOf(
            DeviceActivityReporter.VersionStats(
                version = "1.0.0",
                count = 2,
                percent = 50.0,
            ),
            DeviceActivityReporter.VersionStats(
                version = "<unknown>",
                count = 2,
                percent = 50.0,
            ),
        )
    }

    @Test
    fun `version display is sanitized for logs`() {
        DeviceActivityReporter.sanitizeVersionForLog("  octi\n1\t  ") shouldBe "octi 1"

        val longVersion = "v" + "a".repeat(100)
        val sanitized = DeviceActivityReporter.sanitizeVersionForLog(longVersion)

        sanitized.length shouldBe 80
        sanitized.endsWith("...") shouldBe true
    }

    @Test
    fun `app version is extracted from octi user-agent style versions`() {
        DeviceActivityReporter.appVersionForLog("octi/1.0.0-beta0/FOSS/dev-54f6d23a") shouldBe "1.0.0-beta0"
        DeviceActivityReporter.appVersionForLog("octi/0.11.2-rc0/GPLAY") shouldBe "0.11.2-rc0"
        DeviceActivityReporter.appVersionForLog("1.0.0-beta0") shouldBe "1.0.0-beta0"
        DeviceActivityReporter.appVersionForLog("octi/") shouldBe "<unknown>"
        DeviceActivityReporter.appVersionForLog(null) shouldBe "<unknown>"
    }

    @Test
    fun `build flavor is parsed from user-agent style versions`() {
        DeviceActivityReporter.buildFlavorForLog("octi/1.2.3/FOSS") shouldBe "FOSS"
        DeviceActivityReporter.buildFlavorForLog("octi/0.11.2-rc0/GPLAY") shouldBe "GPLAY"
        DeviceActivityReporter.buildFlavorForLog("octi/0.13.0-rc0/FOSS") shouldBe "FOSS"
        DeviceActivityReporter.buildFlavorForLog("octi/1.2.3/gplay/dev-a1b2c3d") shouldBe "GPLAY"
        DeviceActivityReporter.buildFlavorForLog("octi/1.2.3/GPLATE") shouldBe "GPLAY"
        DeviceActivityReporter.buildFlavorForLog("1.2.3") shouldBe "<unknown>"
        DeviceActivityReporter.buildFlavorForLog(null) shouldBe "<unknown>"
    }

    @Test
    fun `tracked octi user agent with unknown flavor is kept but grouped as unknown flavor`() {
        val activity = mapOf(
            randomKey() to TrackedActivity("octi/1.0.0-beta0/MODDED/dev-d0a81273", seenAt = now),
        )

        val report = DeviceActivityReporter.buildReport(activity = activity, now = now)

        report.oneHour.versionCounts() shouldBe mapOf("1.0.0-beta0" to 1)
        report.oneHour.buildFlavorCounts() shouldBe mapOf("<unknown>" to 1)
    }

    @Test
    fun `auth-failure section is empty when no failures occurred`() {
        val report = DeviceActivityReporter.buildReport(activity = emptyMap(), now = now)

        report.oneHour.authFailures.total shouldBe 0
        report.oneHour.authFailures.byReason shouldBe emptyList()
    }

    @Test
    fun `auth-failure section groups by reason then by user agent`() {
        val failures = listOf(
            AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "bad-credentials", "octi/1.0.0/FOSS"),
            AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "bad-credentials", "octi/1.0.0/FOSS"),
            AuthFailureEvent(
                seenAt = now.minus(Duration.ofMinutes(5)),
                reasonTag = "bad-credentials",
                userAgent = "ktor-client",
                source = AUTH_FAILURE_SOURCE_WS,
            ),
            AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "unknown-device", "octi/0.9.0/GPLAY"),
        )

        val report = DeviceActivityReporter.buildReport(
            activity = emptyMap(),
            failures = failures,
            now = now,
        )

        report.oneHour.authFailures.total shouldBe 4
        report.oneHour.authFailures.byReason.map { it.reasonTag to it.count } shouldBe listOf(
            "bad-credentials" to 3,
            "unknown-device" to 1,
        )
        report.oneHour.authFailures.byReason[0].sources shouldBe listOf(
            DeviceActivityReporter.SourceCount(AUTH_FAILURE_SOURCE_HTTP, 2),
            DeviceActivityReporter.SourceCount(AUTH_FAILURE_SOURCE_WS, 1),
        )
        report.oneHour.authFailures.byReason[0].topUserAgents shouldBe listOf(
            DeviceActivityReporter.UserAgentCount("octi/1.0.0/FOSS", 2),
            DeviceActivityReporter.UserAgentCount("ktor-client", 1),
        )
        report.oneHour.authFailures.byReason[1].topUserAgents shouldBe listOf(
            DeviceActivityReporter.UserAgentCount("octi/0.9.0/GPLAY", 1),
        )
    }

    @Test
    fun `auth-failure section excludes events older than the window`() {
        val failures = listOf(
            AuthFailureEvent(now.minus(Duration.ofMinutes(30)), "bad-credentials", "octi/1.0.0/FOSS"),
            AuthFailureEvent(now.minus(Duration.ofHours(2)), "bad-credentials", "octi/1.0.0/FOSS"),
        )

        val report = DeviceActivityReporter.buildReport(
            activity = emptyMap(),
            failures = failures,
            now = now,
        )

        report.oneHour.authFailures.total shouldBe 1
        report.twentyFourHours.authFailures.total shouldBe 2
    }

    @Test
    fun `auth-failure section caps top user agents per reason`() {
        val failures = (1..10).flatMap { i ->
            List(i) { AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "bad-credentials", "ua-$i") }
        }

        val report = DeviceActivityReporter.buildReport(
            activity = emptyMap(),
            failures = failures,
            now = now,
        )

        val reason = report.oneHour.authFailures.byReason.single()
        reason.count shouldBe failures.size
        reason.topUserAgents.size shouldBe 5
        reason.topUserAgents.first().userAgent shouldBe "ua-10"
        reason.topUserAgents.last().userAgent shouldBe "ua-6"
    }

    @Test
    fun `auth-failure formats with reason and ua breakdown`() {
        val activity = mapOf(
            randomKey() to TrackedActivity("octi/1.0.0/FOSS", seenAt = now),
        )
        val failures = listOf(
            AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "bad-credentials", "octi/1.0.0/FOSS"),
            AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "bad-credentials", "octi/1.0.0/FOSS"),
            AuthFailureEvent(now.minus(Duration.ofMinutes(5)), "missing-device-id", ""),
        )

        val report = DeviceActivityReporter.buildReport(
            activity = activity,
            failures = failures,
            now = now,
        )

        DeviceActivityReporter.formatReport(report) shouldBe
            """
            device-stats:
              1h: total=1
                flavors:
                  FOSS=1 (100.0%)
                versions:
                  1.0.0=1 (100.0%)
                auth-failures: total=3
                  bad-credentials=2 (http=2)
                    octi/1.0.0/FOSS=2
                  missing-device-id=1 (http=1)
                    <unknown>=1
              24h: total=1
                flavors:
                  FOSS=1 (100.0%)
                versions:
                  1.0.0=1 (100.0%)
                auth-failures: total=3
                  bad-credentials=2 (http=2)
                    octi/1.0.0/FOSS=2
                  missing-device-id=1 (http=1)
                    <unknown>=1
            """.trimIndent()
    }

    private fun DeviceActivityReporter.WindowStats.versionCounts(): Map<String, Int> {
        return versions.associate { it.version to it.count }
    }

    private fun DeviceActivityReporter.WindowStats.buildFlavorCounts(): Map<String, Int> {
        return buildFlavors.associate { it.flavor to it.count }
    }

    private fun randomKey(): DeviceKey = DeviceKey(UUID.randomUUID(), UUID.randomUUID())
}
