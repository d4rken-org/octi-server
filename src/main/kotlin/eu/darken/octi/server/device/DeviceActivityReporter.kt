package eu.darken.octi.server.device

import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.launchPeriodicJob
import eu.darken.octi.server.ws.ConnectionRegistry
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceActivityReporter @Inject constructor(
    appScope: AppScope,
    private val deviceRepo: DeviceRepo,
    private val connectionRegistry: ConnectionRegistry,
) {

    data class Report(
        val oneHour: WindowStats,
        val twentyFourHours: WindowStats,
    )

    data class WindowStats(
        val label: String,
        val total: Int,
        val versions: List<VersionStats>,
        val buildFlavors: List<BuildFlavorStats>,
    )

    data class VersionStats(
        val version: String,
        val count: Int,
        val percent: Double,
    )

    data class BuildFlavorStats(
        val flavor: String,
        val count: Int,
        val percent: Double,
    )

    init {
        appScope.launchPeriodicJob(
            tag = TAG,
            interval = REPORT_INTERVAL,
            initialDelay = REPORT_INTERVAL,
            onErrorMessage = "Device activity report failed",
        ) {
            logReport()
        }
    }

    internal fun logReport(now: Instant = Instant.now()) {
        val report = buildReport(
            devices = deviceRepo.allDevices(),
            activeDeviceKeys = connectionRegistry.activeDeviceKeys(),
            now = now,
        )
        log(TAG, INFO) { formatReport(report) }
    }

    companion object {
        private val REPORT_INTERVAL: Duration = Duration.ofMinutes(5)
        private val ONE_HOUR: Duration = Duration.ofHours(1)
        private val TWENTY_FOUR_HOURS: Duration = Duration.ofHours(24)
        private const val UNKNOWN_VERSION = "<unknown>"
        private const val UNKNOWN_BUILD_FLAVOR = "<unknown>"
        private const val MAX_VERSION_DISPLAY_LENGTH = 80
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}]+")
        private val BUILD_FLAVOR_TOKEN_SEPARATOR = Regex("[^A-Za-z0-9]+")
        private val BUILD_FLAVOR_ORDER = listOf("FOSS", "GPLAY", UNKNOWN_BUILD_FLAVOR)
        private val TAG = logTag("Device", "Activity")

        internal fun buildReport(
            devices: Collection<Device>,
            activeDeviceKeys: Set<DeviceKey>,
            now: Instant = Instant.now(),
        ): Report = Report(
            oneHour = buildWindowStats("1h", ONE_HOUR, devices, activeDeviceKeys, now),
            twentyFourHours = buildWindowStats("24h", TWENTY_FOUR_HOURS, devices, activeDeviceKeys, now),
        )

        internal fun formatReport(report: Report): String {
            return buildString {
                appendLine("device-stats:")
                appendWindow(report.oneHour)
                appendWindow(report.twentyFourHours)
            }.trimEnd()
        }

        internal fun sanitizeVersionForLog(raw: String?): String {
            val sanitized = raw
                ?.replace(CONTROL_CHARS, " ")
                ?.trim()
                ?.ifBlank { null }
                ?: return UNKNOWN_VERSION

            return if (sanitized.length <= MAX_VERSION_DISPLAY_LENGTH) {
                sanitized
            } else {
                sanitized.take(MAX_VERSION_DISPLAY_LENGTH - 3) + "..."
            }
        }

        internal fun buildFlavorForLog(raw: String?): String {
            val sanitized = raw
                ?.replace(CONTROL_CHARS, " ")
                ?.trim()
                ?.ifBlank { null }
                ?: return UNKNOWN_BUILD_FLAVOR

            val tokens = sanitized.split(BUILD_FLAVOR_TOKEN_SEPARATOR)

            return when {
                tokens.any { it.equals("FOSS", ignoreCase = true) } -> "FOSS"
                tokens.any { it.equals("GPLAY", ignoreCase = true) || it.equals("GPLATE", ignoreCase = true) } -> "GPLAY"
                else -> UNKNOWN_BUILD_FLAVOR
            }
        }

        private fun buildWindowStats(
            label: String,
            window: Duration,
            devices: Collection<Device>,
            activeDeviceKeys: Set<DeviceKey>,
            now: Instant,
        ): WindowStats {
            val cutoff = now.minus(window)
            val activeDevices = devices.filter { device ->
                !device.lastSeen.isBefore(cutoff) || device.key in activeDeviceKeys
            }

            val versionCounts = activeDevices
                .groupingBy { sanitizeVersionForLog(it.version) }
                .eachCount()

            val buildFlavorCounts = activeDevices
                .groupingBy { buildFlavorForLog(it.version) }
                .eachCount()

            val total = activeDevices.size
            val versions = versionCounts.entries
                .map { (version, count) ->
                    VersionStats(
                        version = version,
                        count = count,
                        percent = if (total == 0) 0.0 else count * 100.0 / total,
                    )
                }
                .sortedWith(compareByDescending<VersionStats> { it.count }.thenBy { it.version })

            val buildFlavors = buildFlavorCounts.entries
                .map { (flavor, count) ->
                    BuildFlavorStats(
                        flavor = flavor,
                        count = count,
                        percent = if (total == 0) 0.0 else count * 100.0 / total,
                    )
                }
                .sortedBy { BUILD_FLAVOR_ORDER.indexOf(it.flavor).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }

            return WindowStats(
                label = label,
                total = total,
                versions = versions,
                buildFlavors = buildFlavors,
            )
        }

        private fun StringBuilder.appendWindow(stats: WindowStats) {
            appendLine("  ${stats.label}: total=${stats.total}")
            appendStatsSection(
                label = "flavors",
                entries = stats.buildFlavors.map { "${it.flavor}=${it.count} (${formatPercent(it.percent)}%)" },
            )
            appendStatsSection(
                label = "versions",
                entries = stats.versions.map { "${it.version}=${it.count} (${formatPercent(it.percent)}%)" },
            )
        }

        private fun StringBuilder.appendStatsSection(label: String, entries: List<String>) {
            appendLine("    $label:")
            if (entries.isEmpty()) {
                appendLine("      <none>")
            } else {
                entries.forEach { appendLine("      $it") }
            }
        }

        private fun formatPercent(percent: Double): String = String.format(Locale.US, "%.1f", percent)
    }
}
