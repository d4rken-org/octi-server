package eu.darken.octi.server

import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.DiskSpaceProbe
import eu.darken.octi.server.common.IpHelper
import eu.darken.octi.server.common.RateLimitConfig
import eu.darken.octi.server.common.debug.logging.ConsoleLogger
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.DebugFlagMonitor
import eu.darken.octi.server.module.StartupRecoveryService
import eu.darken.octi.server.module.UploadSessionRepo
import java.nio.file.Path
import java.time.Duration
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.reflect.full.memberProperties


class App @Inject constructor(
    val appScope: AppScope,
    private val server: Server,
    private val startupRecovery: StartupRecoveryService,
    private val sessionRepo: UploadSessionRepo,
    private val diskSpaceProbe: DiskSpaceProbe,
    @Suppress("unused") private val debugFlagMonitor: DebugFlagMonitor,
) {

    fun launch() {
        startupRecovery.recover()
        sessionRepo.startGC()
        diskSpaceProbe.checkAndLogStartup()
        server.start()
    }

    fun isRunning(): Boolean {
        return server.isRunning()
    }

    fun shutdown() {
        server.stop()
        appScope.cancel()
    }

    data class Config(
        val isDebug: Boolean = false,
        val port: Int,
        val dataPath: Path,
        val rateLimit: RateLimitConfig? = RateLimitConfig(),
        val payloadLimit: Long? = 128 * 1024L,
        val accountGCInterval: Duration = Duration.ofMinutes(10),
        val shareExpiration: Duration = Duration.ofMinutes(60),
        val shareGCInterval: Duration = Duration.ofMinutes(10),
        val deviceExpiration: Duration = Duration.ofDays(90),
        val deviceGCInterval: Duration = Duration.ofMinutes(10),
        val moduleExpiration: Duration = Duration.ofDays(90),
        val moduleGCInterval: Duration = Duration.ofMinutes(10),
        // Storage quota settings
        val accountQuotaBytes: Long = 50L * 1024 * 1024, // 50 MB default
        val maxBlobBytes: Long = 10L * 1024 * 1024, // 10 MB default
        val maxModuleDocumentBytes: Long = 256L * 1024, // 256 KB default
        val minFreeDiskSpaceBytes: Long = 500L * 1024 * 1024, // 500 MB default
        val maxActiveUploadSessionsPerDevice: Int = 8,
        val maxActiveUploadSessionsPerAccount: Int = 32,
        val idleSessionTtlSeconds: Long = 3600, // 1 hour for ACTIVE state
        val completeIdleTtlSeconds: Long = 600, // 10 min for COMPLETE state — bounds finalize-but-no-commit
        val absoluteSessionTtlSeconds: Long = 86400, // 24 hours
        val maxBlobPatchBytes: Long = 1L * 1024 * 1024, // 1 MB per chunk
        // Count caps — bound dirent/inode growth that quota alone cannot reach.
        val maxDevicesPerAccount: Int = 64,
        val maxModulesPerDevice: Int = 256,
        val maxBlobRefsPerModule: Int = 64,
        // Per-account rate budget (layered on top of per-IP rate limit).
        val accountRateLimit: Int = 256,
        val accountRateLimitWindowSeconds: Long = 60,
        val trustedProxyIps: Set<String> = IpHelper.DEFAULT_TRUSTED_PROXY_IPS,
    )

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            log(TAG, INFO) { "Program arguments: ${args.joinToString()}" }
            createComponent(parseConfig(args)).application().launch()
        }

        internal fun parseConfig(args: Array<String>): Config {
            val defaults = Config(
                port = 0,
                dataPath = Path("/tmp/placeholder"),
            )

            val rateLimitsDisabled = args.any { it.startsWith("--disable-rate-limits") }
            return Config(
                isDebug = args.any { it.startsWith("--debug") },
                port = parseIntFlag(args, "--port", min = 1, max = 65535) ?: 8080,
                dataPath = args
                    .single { it.startsWith("--datapath=") }
                    .let { Path(it.substringAfter('=')) }
                    .absolute(),
                rateLimit = if (rateLimitsDisabled) {
                    null
                } else {
                    RateLimitConfig(
                        limit = parseIntFlag(args, "--rate-limit", min = 1) ?: RateLimitConfig().limit,
                        resetTime = Duration.ofSeconds(
                            parseLongFlag(args, "--rate-limit-window-seconds", min = 1)
                                ?: RateLimitConfig().resetTime.toSeconds()
                        ),
                    )
                },
                payloadLimit = parseSizeFlag(args, "--payload-limit-kb", 1024L) ?: defaults.payloadLimit,
                accountQuotaBytes = parseSizeFlag(args, "--account-quota-mb", 1024L * 1024L)
                    ?: defaults.accountQuotaBytes,
                maxBlobBytes = parseSizeFlag(args, "--max-blob-mb", 1024L * 1024L)
                    ?: defaults.maxBlobBytes,
                maxModuleDocumentBytes = parseSizeFlag(args, "--max-module-document-kb", 1024L)
                    ?: defaults.maxModuleDocumentBytes,
                minFreeDiskSpaceBytes = parseSizeFlag(args, "--min-free-disk-mb", 1024L * 1024L)
                    ?: defaults.minFreeDiskSpaceBytes,
                maxActiveUploadSessionsPerDevice = parseIntFlag(args, "--max-upload-sessions-per-device", min = 1)
                    ?: defaults.maxActiveUploadSessionsPerDevice,
                maxActiveUploadSessionsPerAccount = parseIntFlag(args, "--max-upload-sessions-per-account", min = 1)
                    ?: defaults.maxActiveUploadSessionsPerAccount,
                idleSessionTtlSeconds = parseLongFlag(args, "--idle-session-ttl-seconds", min = 1)
                    ?: defaults.idleSessionTtlSeconds,
                completeIdleTtlSeconds = parseLongFlag(args, "--complete-idle-session-ttl-seconds", min = 1)
                    ?: defaults.completeIdleTtlSeconds,
                absoluteSessionTtlSeconds = parseLongFlag(args, "--absolute-session-ttl-seconds", min = 1)
                    ?: defaults.absoluteSessionTtlSeconds,
                maxBlobPatchBytes = parseSizeFlag(args, "--max-blob-patch-kb", 1024L)
                    ?: defaults.maxBlobPatchBytes,
                maxDevicesPerAccount = parseIntFlag(args, "--max-devices-per-account", min = 1)
                    ?: defaults.maxDevicesPerAccount,
                maxModulesPerDevice = parseIntFlag(args, "--max-modules-per-device", min = 1)
                    ?: defaults.maxModulesPerDevice,
                maxBlobRefsPerModule = parseIntFlag(args, "--max-blob-refs-per-module", min = 1)
                    ?: defaults.maxBlobRefsPerModule,
                accountRateLimit = if (rateLimitsDisabled) 0
                    else parseIntFlag(args, "--account-rate-limit", min = 1) ?: defaults.accountRateLimit,
                accountRateLimitWindowSeconds = parseLongFlag(args, "--account-rate-limit-window-seconds", min = 1)
                    ?: defaults.accountRateLimitWindowSeconds,
                trustedProxyIps = parseTrustedProxyIps(args) ?: defaults.trustedProxyIps,
            )
        }

        /**
         * Parses a "--flag=N" arg into N * [unitBytes]. Returns null if the flag isn't
         * present. Throws on malformed/non-positive values so a typo'd config
         * fails the boot rather than silently flipping to a default.
         */
        internal fun parseSizeFlag(args: Array<String>, flag: String, unitBytes: Long): Long? {
            val matches = args.filter { it.startsWith("$flag=") }
            if (matches.isEmpty()) return null
            require(matches.size == 1) { "$flag specified more than once: ${matches.joinToString(" ")}" }
            val rawValue = matches.single().substringAfter('=')
            val value = rawValue.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid value for $flag: '$rawValue'")
            require(value > 0) { "$flag must be positive, got $value" }
            return try {
                Math.multiplyExact(value, unitBytes)
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("$flag value $value overflows when scaled by $unitBytes")
            }
        }

        internal fun parseIntFlag(args: Array<String>, flag: String, min: Int, max: Int = Int.MAX_VALUE): Int? {
            val value = parseLongFlag(args, flag, min.toLong(), max.toLong()) ?: return null
            return value.toInt()
        }

        internal fun parseLongFlag(args: Array<String>, flag: String, min: Long, max: Long = Long.MAX_VALUE): Long? {
            val matches = args.filter { it.startsWith("$flag=") }
            if (matches.isEmpty()) return null
            require(matches.size == 1) { "$flag specified more than once: ${matches.joinToString(" ")}" }
            val rawValue = matches.single().substringAfter('=')
            val value = rawValue.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid value for $flag: '$rawValue'")
            require(value in min..max) { "$flag must be between $min and $max, got $value" }
            return value
        }

        internal fun parseTrustedProxyIps(args: Array<String>): Set<String>? {
            val matches = args.filter { it.startsWith("--trusted-proxy-ips=") }
            if (matches.isEmpty()) return null
            require(matches.size == 1) { "--trusted-proxy-ips specified more than once: ${matches.joinToString(" ")}" }
            return matches.single()
                .substringAfter('=')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    IpHelper.parseIpOrNull(it)
                        ?: throw IllegalArgumentException("Invalid value for --trusted-proxy-ips: '$it'")
                }
                .toSet()
        }

        fun createComponent(config: Config): AppComponent {
            log(TAG, INFO) { "SERVER BUILD: ${BuildInfo.GIT_SHA} (${BuildInfo.GIT_DATE})" }

            log(TAG, INFO) { "App config is\n---" }
            Config::class.memberProperties.forEach { prop -> log(TAG, INFO) { "${prop.name}: ${prop.get(config)}" } }
            log(TAG, INFO) { "---" }

            if (config.isDebug) {
                ConsoleLogger.logLevel = VERBOSE
                log(TAG, VERBOSE) { "Debug mode is active" }
                log(TAG, DEBUG) { "Debug mode is active" }
                log(TAG, INFO) { "Debug mode is active" }
            } else {
                ConsoleLogger.logLevel = INFO
                log(TAG, INFO) { "Debug mode disabled" }
            }

            return DaggerAppComponent.builder().config(config).build()
        }

        private val TAG = logTag("App")
    }
}
