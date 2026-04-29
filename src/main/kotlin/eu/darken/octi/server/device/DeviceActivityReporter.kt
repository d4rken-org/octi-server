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
    )

    data class VersionStats(
        val version: String,
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
        private const val MAX_VERSION_DISPLAY_LENGTH = 80
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}]+")
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
            return "device-stats: ${formatWindow(report.oneHour)}; ${formatWindow(report.twentyFourHours)}"
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

            return WindowStats(
                label = label,
                total = total,
                versions = versions,
            )
        }

        private fun formatWindow(stats: WindowStats): String {
            val versions = stats.versions.joinToString(prefix = "[", postfix = "]") {
                "${it.version}=${it.count} (${formatPercent(it.percent)}%)"
            }
            return "${stats.label} total=${stats.total} versions=$versions"
        }

        private fun formatPercent(percent: Double): String = String.format(Locale.US, "%.1f", percent)
    }
}
