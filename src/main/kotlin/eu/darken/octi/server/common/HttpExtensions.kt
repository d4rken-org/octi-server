package eu.darken.octi.server.common

import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceId
import eu.darken.octi.server.device.DeviceKey
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.device.DeviceCredentials
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import java.time.Instant
import java.util.*

val IpDeviceTrackerKey = AttributeKey<IpDeviceTracker>("IpDeviceTracker")

private val TAG = logTag("Auth")

fun parseDeviceId(header: String?): DeviceId? {
    if (header.isNullOrBlank()) return null
    return try {
        UUID.fromString(header)
    } catch (e: IllegalArgumentException) {
        log(TAG, WARN) { "Invalid device ID" }
        null
    }
}

val RoutingCall.headerDeviceId: DeviceId?
    get() = parseDeviceId(request.header("X-Device-ID"))

sealed interface AuthResult {
    data class Success(val deviceId: DeviceId, val device: Device) : AuthResult
    data class Failure(val reason: String, val status: HttpStatusCode) : AuthResult
}

data class DeviceMetadataPatch(
    val version: String? = null,
    val platform: String? = null,
    val label: String? = null,
)

fun normalizeLabel(raw: String?): String? = raw?.trim()?.take(128)?.ifBlank { null }

/**
 * Validates the auth headers and returns the device on success — no side effects.
 * Use [touchAuthenticatedDevice] to record lastSeen/IP after the per-account rate
 * limit gate has accepted the call. Splitting validate from touch keeps over-limit
 * requests from updating device metadata.
 */
suspend fun authenticateDevice(
    deviceIdHeader: String?,
    authHeader: String?,
    deviceRepo: DeviceRepo,
): AuthResult {
    val deviceId = parseDeviceId(deviceIdHeader)
        ?: return AuthResult.Failure("X-Device-ID header is missing", HttpStatusCode.BadRequest)

    val creds = DeviceCredentials.parseFromHeader(authHeader)
        ?: return AuthResult.Failure("Device credentials are missing", HttpStatusCode.BadRequest)

    val device = deviceRepo.getDevice(DeviceKey(creds.accountId, deviceId))
        ?: return AuthResult.Failure("Unknown device: $deviceId", HttpStatusCode.NotFound)

    if (!device.isAuthorized(creds)) {
        return AuthResult.Failure("Device credentials not found or insufficient", HttpStatusCode.Unauthorized)
    }

    return AuthResult.Success(deviceId, device)
}

/**
 * Records lastSeen + optional metadata + IP-device association for an already-authenticated
 * device. Called only after the per-account rate-limit gate accepts the request, so over-limit
 * traffic doesn't churn device metadata.
 */
suspend fun touchAuthenticatedDevice(
    device: Device,
    deviceRepo: DeviceRepo,
    clientIp: String? = null,
    ipTracker: IpDeviceTracker? = null,
    metadata: DeviceMetadataPatch? = null,
): Device {
    deviceRepo.updateDevice(device.key) {
        var updated = it.copy(lastSeen = Instant.now())
        metadata?.version?.let { v -> updated = updated.copy(version = v) }
        metadata?.platform?.let { p -> updated = updated.copy(platform = p) }
        metadata?.label?.let { l -> updated = updated.copy(label = l) }
        updated
    }
    if (clientIp != null && ipTracker != null) {
        try {
            ipTracker.record(clientIp, device.accountId, device.id)
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to record IP device tracking: ${e.message}" }
        }
    }
    return deviceRepo.getDevice(device.key) ?: device
}

/**
 * Parses an entity-tag for `If-Match` / `If-None-Match`.
 * Accepts `*`, `"opaque"`, and bare `opaque` (legacy clients).
 * Rejects weak (`W/"..."`) — If-Match requires strong comparison (RFC 7232 §3.1).
 * Returns null for malformed input so the caller can respond 400.
 */
fun parseStrongEtag(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed == "*") return "*"
    if (trimmed.startsWith("W/", ignoreCase = true)) return null
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
        return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
}

suspend fun RoutingContext.verifyCaller(tag: String, deviceRepo: DeviceRepo): Device? {
    val ipTracker = call.application.attributes.getOrNull(IpDeviceTrackerKey)
    val accountRateLimiter = call.application.attributes.getOrNull(AccountRateLimiterKey)

    // 1. Validate credentials (no side effects).
    val result = authenticateDevice(
        deviceIdHeader = call.request.header("X-Device-ID"),
        authHeader = call.request.header("Authorization"),
        deviceRepo = deviceRepo,
    )
    val device = when (result) {
        is AuthResult.Success -> result.device
        is AuthResult.Failure -> {
            log(tag, WARN) { "verifyAuth(): ${result.reason}" }
            call.respond(result.status, result.reason)
            return null
        }
    }

    // 2. Per-account rate-limit gate. Over-limit calls don't get to update lastSeen.
    if (accountRateLimiter != null && !accountRateLimiter.tryAcquire(device.accountId)) {
        call.respond(HttpStatusCode.TooManyRequests, "Account rate limit exceeded")
        return null
    }

    // 3. Record metadata only for accepted calls.
    return touchAuthenticatedDevice(
        device = device,
        deviceRepo = deviceRepo,
        clientIp = call.request.clientIp(),
        ipTracker = ipTracker,
        metadata = DeviceMetadataPatch(
            version = call.request.header("Octi-Device-Version"),
            platform = call.request.header("Octi-Device-Platform"),
            label = normalizeLabel(call.request.header("Octi-Device-Label")),
        ),
    )
}