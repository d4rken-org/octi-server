package eu.darken.octi.server.device

import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.launchPeriodicJob
import eu.darken.octi.server.device.DeviceClientIdentityTracker.AuthFailureEvent
import eu.darken.octi.server.device.DeviceClientIdentityTracker.TrackedActivity
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceActivityReporter @Inject constructor(
    appScope: AppScope,
    private val deviceRepo: DeviceRepo,
    private val clientIdentityTracker: DeviceClientIdentityTracker,
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
        val authFailures: AuthFailureStats,
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

    data class AuthFailureStats(
        val total: Int,
        val byReason: List<ReasonBreakdown>,
    )

    data class ReasonBreakdown(
        val reasonTag: String,
        val count: Int,
        val sources: List<SourceCount>,
        val topUserAgents: List<UserAgentCount>,
    )

    data class SourceCount(
        val source: String,
        val count: Int,
    )

    data class UserAgentCount(
        val userAgent: String,
        val count: Int,
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
        clientIdentityTracker.retainDevices(deviceRepo.allDevices().map { it.key }.toSet())
        val report = buildReport(
            activity = clientIdentityTracker.snapshotActivity(),
            failures = clientIdentityTracker.snapshotAuthFailures(now),
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
        private const val UNKNOWN_USER_AGENT = "<unknown>"
        private const val UNKNOWN_SOURCE = "<unknown>"
        private const val MAX_VERSION_DISPLAY_LENGTH = 80
        private const val FAILURE_TOP_USER_AGENTS = 5
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}]+")
        private val BUILD_FLAVOR_TOKEN_SEPARATOR = Regex("[^A-Za-z0-9]+")
        private val BUILD_FLAVOR_ORDER = listOf("FOSS", "GPLAY", UNKNOWN_BUILD_FLAVOR)
        private val SOURCE_ORDER = listOf(AUTH_FAILURE_SOURCE_HTTP, AUTH_FAILURE_SOURCE_WS, UNKNOWN_SOURCE)
        private val TAG = logTag("Device", "Activity")

        internal fun buildReport(
            activity: Map<DeviceKey, TrackedActivity>,
            failures: List<AuthFailureEvent> = emptyList(),
            now: Instant = Instant.now(),
        ): Report = Report(
            oneHour = buildWindowStats("1h", ONE_HOUR, activity, failures, now),
            twentyFourHours = buildWindowStats("24h", TWENTY_FOUR_HOURS, activity, failures, now),
        )

        internal fun formatReport(report: Report): String {
            return buildString {
                appendLine("device-stats:")
                appendWindow(report.oneHour)
                appendWindow(report.twentyFourHours)
            }.trimEnd()
        }

        internal fun sanitizeVersionForLog(raw: String?): String {
            val sanitized = sanitizeForLog(raw)
                ?: return UNKNOWN_VERSION

            return truncateVersionForLog(sanitized)
        }

        internal fun appVersionForLog(raw: String?): String {
            val sanitized = sanitizeForLog(raw)
                ?: return UNKNOWN_VERSION

            val version = if (sanitized.startsWith("octi/", ignoreCase = true)) {
                sanitized.split("/", limit = 3).getOrNull(1)?.trim()?.ifBlank { null }
            } else {
                sanitized
            } ?: return UNKNOWN_VERSION

            return truncateVersionForLog(version)
        }

        internal fun buildFlavorForLog(raw: String?): String {
            val sanitized = sanitizeForLog(raw)
                ?: return UNKNOWN_BUILD_FLAVOR

            val tokens = sanitized.split(BUILD_FLAVOR_TOKEN_SEPARATOR)

            return when {
                tokens.any { it.equals("FOSS", ignoreCase = true) } -> "FOSS"
                tokens.any { it.equals("GPLAY", ignoreCase = true) || it.equals("GPLATE", ignoreCase = true) } -> "GPLAY"
                else -> UNKNOWN_BUILD_FLAVOR
            }
        }

        private fun sanitizeForLog(raw: String?): String? {
            return raw
                ?.replace(CONTROL_CHARS, " ")
                ?.trim()
                ?.ifBlank { null }
        }

        private fun truncateVersionForLog(sanitized: String): String {
            return if (sanitized.length <= MAX_VERSION_DISPLAY_LENGTH) {
                sanitized
            } else {
                sanitized.take(MAX_VERSION_DISPLAY_LENGTH - 3) + "..."
            }
        }

        private fun userAgentLabel(raw: String): String {
            return sanitizeForLog(raw)
                ?.let { truncateVersionForLog(it) }
                ?: UNKNOWN_USER_AGENT
        }

        private fun sourceLabel(raw: String): String {
            return sanitizeForLog(raw)
                ?.take(32)
                ?: UNKNOWN_SOURCE
        }

        private fun buildWindowStats(
            label: String,
            window: Duration,
            activity: Map<DeviceKey, TrackedActivity>,
            failures: List<AuthFailureEvent>,
            now: Instant,
        ): WindowStats {
            val cutoff = now.minus(window)
            val activeDevices = activity.values.filter { !it.seenAt.isBefore(cutoff) }

            val versionCounts = activeDevices
                .groupingBy { appVersionForLog(it.userAgent.ifEmpty { null }) }
                .eachCount()

            val buildFlavorCounts = activeDevices
                .groupingBy { buildFlavorForLog(it.userAgent.ifEmpty { null }) }
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

            val authFailureStats = buildAuthFailureStats(failures, cutoff)

            return WindowStats(
                label = label,
                total = total,
                versions = versions,
                buildFlavors = buildFlavors,
                authFailures = authFailureStats,
            )
        }

        private fun buildAuthFailureStats(
            failures: List<AuthFailureEvent>,
            cutoff: Instant,
        ): AuthFailureStats {
            val windowed = failures.filter { !it.seenAt.isBefore(cutoff) }
            val byReason = windowed
                .groupBy { it.reasonTag }
                .map { (reason, events) ->
                    val sourceCounts = events
                        .groupingBy { sourceLabel(it.source) }
                        .eachCount()
                        .entries
                        .map { SourceCount(it.key, it.value) }
                        .sortedWith(compareBy<SourceCount> {
                            SOURCE_ORDER.indexOf(it.source).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                        }.thenBy { it.source })

                    val topUserAgents = events
                        .groupingBy { userAgentLabel(it.userAgent) }
                        .eachCount()
                        .entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .take(FAILURE_TOP_USER_AGENTS)
                        .map { UserAgentCount(it.key, it.value) }
                    ReasonBreakdown(
                        reasonTag = reason,
                        count = events.size,
                        sources = sourceCounts,
                        topUserAgents = topUserAgents,
                    )
                }
                .sortedWith(compareByDescending<ReasonBreakdown> { it.count }.thenBy { it.reasonTag })
            return AuthFailureStats(total = windowed.size, byReason = byReason)
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
            appendAuthFailures(stats.authFailures)
        }

        private fun StringBuilder.appendAuthFailures(stats: AuthFailureStats) {
            appendLine("    auth-failures: total=${stats.total}")
            if (stats.byReason.isEmpty()) {
                appendLine("      <none>")
                return
            }
            stats.byReason.forEach { reason ->
                appendLine("      ${reason.reasonTag}=${reason.count}${reason.sources.formatSourceCounts()}")
                reason.topUserAgents.forEach { ua ->
                    appendLine("        ${ua.userAgent}=${ua.count}")
                }
            }
        }

        private fun List<SourceCount>.formatSourceCounts(): String {
            if (isEmpty()) return ""
            return joinToString(prefix = " (", postfix = ")") { "${it.source}=${it.count}" }
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
