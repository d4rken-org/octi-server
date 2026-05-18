package eu.darken.octi.server.common

import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.AUTH_FAILURE_SOURCE_HTTP
import eu.darken.octi.server.device.DeviceClientIdentityTracker
import eu.darken.octi.server.device.DeviceId
import eu.darken.octi.server.device.DeviceKey
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.device.DeviceCredentials
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.*

val IpDeviceTrackerKey = AttributeKey<IpDeviceTracker>("IpDeviceTracker")
val DeviceClientIdentityTrackerKey = AttributeKey<DeviceClientIdentityTracker>("DeviceClientIdentityTracker")

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
    data class Failure(val reason: String, val tag: String, val status: HttpStatusCode) : AuthResult
}

data class DeviceMetadataPatch(
    val version: String? = null,
    val platform: String? = null,
    val label: String? = null,
    val capabilities: Set<String>? = null,
)

fun normalizeLabel(raw: String?): String? = raw?.trim()?.take(128)?.ifBlank { null }

private val capabilityParseJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the `Octi-Device-Capabilities` HTTP header value into a validated [Set] of tag
 * strings. Mirrors the validation in the client-side `CapabilitiesCodec`: max [MAX_CAPABILITY_TAGS]
 * tags, max [MAX_CAPABILITY_TAG_LENGTH] chars each, ASCII `<namespace>:<value>` shape.
 *
 * Returns `null` if the header is absent, blank, malformed, or violates a limit — the device
 * is treated as not reporting capabilities. Drops the whole set on any bad tag (no partial
 * acceptance) so peers see a consistent "either valid or absent" wire contract.
 */
fun parseCapabilitiesHeader(raw: String?): Set<String>? {
    if (raw.isNullOrBlank()) return null
    if (raw.length > MAX_CAPABILITY_HEADER_LENGTH) {
        log(TAG, WARN) { "parseCapabilitiesHeader: header too long (${raw.length})" }
        return null
    }
    val element = try {
        capabilityParseJson.parseToJsonElement(raw)
    } catch (e: SerializationException) {
        log(TAG, WARN) { "parseCapabilitiesHeader: malformed JSON: ${e.message}" }
        return null
    }
    val array = element as? JsonArray ?: run {
        log(TAG, WARN) { "parseCapabilitiesHeader: not a JSON array" }
        return null
    }
    if (array.size > MAX_CAPABILITY_TAGS) {
        log(TAG, WARN) { "parseCapabilitiesHeader: too many tags (${array.size})" }
        return null
    }
    val result = LinkedHashSet<String>(array.size.coerceAtLeast(1))
    for (item in array) {
        val str = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: run {
            log(TAG, WARN) { "parseCapabilitiesHeader: non-string element" }
            return null
        }
        if (str.length > MAX_CAPABILITY_TAG_LENGTH || !CAPABILITY_TAG_REGEX.matches(str)) {
            log(TAG, WARN) { "parseCapabilitiesHeader: invalid tag shape '$str'" }
            return null
        }
        result.add(str)
    }
    return result
}

const val MAX_CAPABILITY_TAGS = 64
const val MAX_CAPABILITY_TAG_LENGTH = 128
const val MAX_CAPABILITY_HEADER_LENGTH = 4096
val CAPABILITY_TAG_REGEX = Regex("""[a-z][a-z0-9]*:[A-Za-z0-9._\-]+""")

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
        ?: return AuthResult.Failure(
            reason = "X-Device-ID header is missing",
            tag = "missing-device-id",
            status = HttpStatusCode.BadRequest,
        )

    val creds = DeviceCredentials.parseFromHeader(authHeader)
        ?: return AuthResult.Failure(
            reason = "Device credentials are missing",
            tag = "missing-credentials",
            status = HttpStatusCode.BadRequest,
        )

    val deviceKey = DeviceKey(creds.accountId, deviceId)
    val device = deviceRepo.getDevice(deviceKey)
        ?: return AuthResult.Failure(
            reason = "Unknown device: $deviceId",
            tag = deviceRepo.classifyMissingDevice(deviceKey).tag,
            status = HttpStatusCode.NotFound,
        )

    if (!device.isAuthorized(creds)) {
        return AuthResult.Failure(
            reason = "Device credentials not found or insufficient",
            tag = "bad-credentials",
            status = HttpStatusCode.Unauthorized,
        )
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
        metadata?.capabilities?.let { c -> updated = updated.copy(capabilities = c) }
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
    val deviceClientIdentityTracker = call.application.attributes.getOrNull(DeviceClientIdentityTrackerKey)

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
            deviceClientIdentityTracker?.recordAuthFailure(
                reasonTag = result.tag,
                rawUserAgent = call.request.userAgent(),
                source = AUTH_FAILURE_SOURCE_HTTP,
            )
            call.respond(result.status, result.reason)
            return null
        }
    }

    deviceClientIdentityTracker?.recordUserAgent(device.key, call.request.userAgent())

    // 2. Per-account rate-limit gate. Over-limit calls don't get to update lastSeen.
    if (accountRateLimiter != null) {
        when (val decision = accountRateLimiter.acquire(device.accountId)) {
            AccountRateLimiter.Decision.Accepted -> Unit
            is AccountRateLimiter.Decision.Rejected -> {
                call.response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, "Account rate limit exceeded")
                return null
            }
        }
    }

    val trustedProxyIps = call.application.attributes.getOrNull(TrustedProxyIpsKey)
        ?: IpHelper.DEFAULT_TRUSTED_PROXY_IPS

    // 3. Record metadata only for accepted calls.
    return touchAuthenticatedDevice(
        device = device,
        deviceRepo = deviceRepo,
        clientIp = call.request.clientIp(trustedProxyIps),
        ipTracker = ipTracker,
        metadata = DeviceMetadataPatch(
            version = call.request.header("Octi-Device-Version"),
            platform = call.request.header("Octi-Device-Platform"),
            label = normalizeLabel(call.request.header("Octi-Device-Label")),
            capabilities = parseCapabilitiesHeader(call.request.header("Octi-Device-Capabilities")),
        ),
    )
}
