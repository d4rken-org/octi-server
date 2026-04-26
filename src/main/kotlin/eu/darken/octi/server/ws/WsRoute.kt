package eu.darken.octi.server.ws

import eu.darken.octi.server.App
import eu.darken.octi.server.common.AccountRateLimiter
import eu.darken.octi.server.common.AuthResult
import eu.darken.octi.server.common.DeviceMetadataPatch
import eu.darken.octi.server.common.IpDeviceTracker
import eu.darken.octi.server.common.authenticateDevice
import eu.darken.octi.server.common.clientIp
import eu.darken.octi.server.common.normalizeLabel
import eu.darken.octi.server.common.touchAuthenticatedDevice
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.DeviceRepo
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WsRoute @Inject constructor(
    private val config: App.Config,
    private val deviceRepo: DeviceRepo,
    private val connectionRegistry: ConnectionRegistry,
    private val ipDeviceTracker: IpDeviceTracker,
    private val accountRateLimiter: AccountRateLimiter,
) {

    fun setup(rootRoute: Routing) {
        rootRoute.webSocket("/v1/ws") {
            val authResult = authenticateDevice(
                deviceIdHeader = call.request.headers["X-Device-ID"],
                authHeader = call.request.headers["Authorization"],
                deviceRepo = deviceRepo,
            )
            val auth = when (authResult) {
                is AuthResult.Success -> authResult
                is AuthResult.Failure -> {
                    log(TAG, WARN) { "WS auth failed: ${authResult.reason}" }
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                    return@webSocket
                }
            }

            // Per-account rate-limit applies to WS connection establishment too.
            if (accountRateLimiter.acquire(auth.device.accountId) is AccountRateLimiter.Decision.Rejected) {
                log(TAG, WARN) { "WS rate-limited: account=${auth.device.accountId}" }
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Account rate limit exceeded"))
                return@webSocket
            }

            val clientIp = call.request.clientIp(config.trustedProxyIps)

            // Record metadata only after the rate-limit gate accepts.
            touchAuthenticatedDevice(
                device = auth.device,
                deviceRepo = deviceRepo,
                clientIp = clientIp,
                ipTracker = ipDeviceTracker,
                metadata = DeviceMetadataPatch(
                    version = call.request.headers["Octi-Device-Version"],
                    platform = call.request.headers["Octi-Device-Platform"],
                    label = normalizeLabel(call.request.headers["Octi-Device-Label"]),
                ),
            )

            val connectedAt = Instant.now()
            val registerResult = connectionRegistry.register(auth.deviceId, auth.device.accountId, clientIp)
            val deviceSession = when (registerResult) {
                is ConnectionRegistry.RegisterResult.Accepted -> registerResult.session
                is ConnectionRegistry.RegisterResult.Rejected -> {
                    log(TAG, WARN) { "Connection rejected for device=${auth.deviceId}: ${registerResult.reason}" }
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, registerResult.reason))
                    return@webSocket
                }
            }
            log(TAG, INFO) { "Connected: device=${auth.deviceId}, account=${auth.device.accountId}" }

            // Forward outbox messages to the WebSocket — must happen in session scope
            val forwarder = launch {
                try {
                    for (message in deviceSession.outbox) {
                        send(Frame.Text(message))
                    }
                    // Outbox closed externally (eviction or replacement) — close the WebSocket
                    close(CloseReason(CloseReason.Codes.GOING_AWAY, "Session replaced"))
                } catch (e: Exception) {
                    when {
                        e is kotlinx.coroutines.channels.ClosedReceiveChannelException -> {
                            log(TAG) { "Outbox closed for device=${auth.deviceId}" }
                        }
                        else -> {
                            log(TAG, WARN) { "Forwarder failed for device=${auth.deviceId}: ${e.message}" }
                        }
                    }
                }
            }

            try {
                var frameCount = 0
                var windowStart = Instant.now()
                for (frame in incoming) {
                    val now = Instant.now()
                    if (Duration.between(windowStart, now).toMillis() >= RATE_LIMIT_WINDOW_MS) {
                        frameCount = 0
                        windowStart = now
                    }
                    frameCount++
                    if (frameCount > MAX_FRAMES_PER_WINDOW) {
                        log(TAG, WARN) { "Frame rate limit exceeded for device=${auth.deviceId}, closing" }
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Frame rate limit exceeded"))
                        break
                    }
                }
            } finally {
                forwarder.cancel()
                withContext(NonCancellable) {
                    connectionRegistry.unregister(deviceSession)
                }
                val duration = Duration.between(connectedAt, Instant.now())
                val durationStr = formatDuration(duration)
                log(TAG, INFO) { "Disconnected: device=${auth.deviceId}, duration=$durationStr" }
            }
        }
    }

    private fun formatDuration(duration: Duration): String {
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m${seconds}s"
            else -> "${seconds}s"
        }
    }

    companion object {
        internal const val RATE_LIMIT_WINDOW_MS = 60_000L // 1 minute
        internal const val MAX_FRAMES_PER_WINDOW = 120
        private val TAG = logTag("WS", "Route")
    }
}
