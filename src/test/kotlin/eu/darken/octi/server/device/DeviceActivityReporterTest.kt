package eu.darken.octi.server.device

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

class DeviceActivityReporterTest {

    private val now: Instant = Instant.parse("2026-04-29T12:00:00Z")

    @Test
    fun `report counts lastSeen windows and currently connected devices`() {
        val oneHour = device(version = "octi/1.0.0/FOSS", lastSeen = now.minus(Duration.ofMinutes(10)))
        val twentyFourHours = device(version = "octi/2.0.0/GPLAY", lastSeen = now.minus(Duration.ofHours(2)))
        val connected = device(version = "octi/3.0.0/GPLAY", lastSeen = now.minus(Duration.ofHours(30)))
        val inactive = device(version = "octi/4.0.0/FOSS", lastSeen = now.minus(Duration.ofHours(30)))

        val report = DeviceActivityReporter.buildReport(
            devices = listOf(oneHour, twentyFourHours, connected, inactive),
            activeDeviceKeys = setOf(connected.key),
            now = now,
        )

        report.oneHour.total shouldBe 2
        report.oneHour.versionCounts() shouldBe mapOf(
            "octi/1.0.0/FOSS" to 1,
            "octi/3.0.0/GPLAY" to 1,
        )
        report.oneHour.buildFlavorCounts() shouldBe mapOf(
            "FOSS" to 1,
            "GPLAY" to 1,
        )

        report.twentyFourHours.total shouldBe 3
        report.twentyFourHours.versionCounts() shouldBe mapOf(
            "octi/1.0.0/FOSS" to 1,
            "octi/2.0.0/GPLAY" to 1,
            "octi/3.0.0/GPLAY" to 1,
        )
        report.twentyFourHours.buildFlavorCounts() shouldBe mapOf(
            "FOSS" to 1,
            "GPLAY" to 2,
        )
    }

    @Test
    fun `report formats counts and percentages sorted by count`() {
        val report = DeviceActivityReporter.buildReport(
            devices = listOf(
                device(version = "octi/1.0.0/FOSS", lastSeen = now),
                device(version = "octi/1.0.0/FOSS", lastSeen = now),
                device(version = "octi/2.0.0/GPLAY", lastSeen = now),
            ),
            activeDeviceKeys = emptySet(),
            now = now,
        )

        DeviceActivityReporter.formatReport(report) shouldBe
            """
            device-stats:
              1h: total=3
                flavors:
                  FOSS=2 (66.7%)
                  GPLAY=1 (33.3%)
                versions:
                  octi/1.0.0/FOSS=2 (66.7%)
                  octi/2.0.0/GPLAY=1 (33.3%)
              24h: total=3
                flavors:
                  FOSS=2 (66.7%)
                  GPLAY=1 (33.3%)
                versions:
                  octi/1.0.0/FOSS=2 (66.7%)
                  octi/2.0.0/GPLAY=1 (33.3%)
            """.trimIndent()
    }

    @Test
    fun `unknown versions are grouped and empty windows are safe`() {
        val empty = DeviceActivityReporter.buildReport(
            devices = emptyList(),
            activeDeviceKeys = emptySet(),
            now = now,
        )
        empty.oneHour.total shouldBe 0
        empty.oneHour.versions shouldBe emptyList()
        empty.oneHour.buildFlavors shouldBe emptyList()

        val report = DeviceActivityReporter.buildReport(
            devices = listOf(
                device(version = null, lastSeen = now),
                device(version = "   ", lastSeen = now),
            ),
            activeDeviceKeys = emptySet(),
            now = now,
        )

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
    fun `sanitized-equivalent versions are grouped together`() {
        val report = DeviceActivityReporter.buildReport(
            devices = listOf(
                device(version = "octi/1.0.0", lastSeen = now),
                device(version = "  octi/1.0.0  ", lastSeen = now),
                device(version = null, lastSeen = now),
                device(version = "\n\t", lastSeen = now),
            ),
            activeDeviceKeys = emptySet(),
            now = now,
        )

        report.oneHour.versions shouldBe listOf(
            DeviceActivityReporter.VersionStats(
                version = "<unknown>",
                count = 2,
                percent = 50.0,
            ),
            DeviceActivityReporter.VersionStats(
                version = "octi/1.0.0",
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
    fun `build flavor is parsed from user-agent style versions`() {
        DeviceActivityReporter.buildFlavorForLog("octi/1.2.3/FOSS") shouldBe "FOSS"
        DeviceActivityReporter.buildFlavorForLog("octi/1.2.3/gplay/dev-a1b2c3d") shouldBe "GPLAY"
        DeviceActivityReporter.buildFlavorForLog("octi/1.2.3/GPLATE") shouldBe "GPLAY"
        DeviceActivityReporter.buildFlavorForLog("1.2.3") shouldBe "<unknown>"
        DeviceActivityReporter.buildFlavorForLog(null) shouldBe "<unknown>"
    }

    private fun DeviceActivityReporter.WindowStats.versionCounts(): Map<String, Int> {
        return versions.associate { it.version to it.count }
    }

    private fun DeviceActivityReporter.WindowStats.buildFlavorCounts(): Map<String, Int> {
        return buildFlavors.associate { it.flavor to it.count }
    }

    private fun device(
        version: String?,
        lastSeen: Instant,
        accountId: UUID = UUID.randomUUID(),
        deviceId: UUID = UUID.randomUUID(),
    ): Device {
        return Device(
            data = Device.Data(
                id = deviceId,
                password = "test-password",
                version = version,
                addedAt = lastSeen,
                lastSeen = lastSeen,
            ),
            path = Path.of("/tmp/$deviceId"),
            accountId = accountId,
        )
    }
}
