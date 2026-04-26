package eu.darken.octi.server.common

import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class RateLimitConfig(
    val limit: Int = 512,
    val resetTime: Duration = Duration.ofSeconds(60),
)

private val TAG = logTag("RateLimiter")
private const val MAX_DEVICES_IN_LOG = 5

private data class ClientRateState(
    val id: String,
    val requests: Int = 0,
    val resetAt: Instant,
)

fun Application.installRateLimit(
    config: RateLimitConfig,
    ipLookup: IpAccountLookup? = null,
    trustedProxyIps: Set<String> = IpHelper.DEFAULT_TRUSTED_PROXY_IPS,
) {
    log(TAG, INFO) { "Rate limits are set to $config" }
    val rateLimitCache = ConcurrentHashMap<String, ClientRateState>()

    launch(Dispatchers.IO) {
        while (currentCoroutineContext().isActive) {
            if (rateLimitCache.isNotEmpty()) {
                val top10 = rateLimitCache.values.sortedByDescending { it.requests }.take(10)
                if (ipLookup != null) {
                    val enriched = top10.joinToString("\n  ") { state ->
                        val context = ipLookup.getContextForIp(state.id)
                        if (context != null) {
                            val devices = context.devices.sorted().let { devs ->
                                val shown = devs.take(MAX_DEVICES_IN_LOG).map { it.shortId() }
                                val remaining = devs.size - shown.size
                                if (remaining > 0) "$shown +$remaining more" else "$shown"
                            }
                            "ip=${state.id} reqs=${state.requests} accounts=${context.accounts.size} devices=$devices"
                        } else {
                            "ip=${state.id} reqs=${state.requests} accounts=? (no auth seen)"
                        }
                    }
                    log(TAG, VERBOSE) { "Rate limit top 10 by requests:\n  $enriched" }
                } else {
                    log(TAG, VERBOSE) { "Rate limit top 10 by requests: $top10" }
                }
                val now = Instant.now()
                val staleEntries = rateLimitCache.filterValues { state -> now.isAfter(state.resetAt) }
                if (staleEntries.isNotEmpty()) {
                    log(TAG) { "Removing ${staleEntries.size} stale rate limit entries" }
                    staleEntries.keys.forEach { rateLimitCache.remove(it) }
                }
            }
            delay(config.resetTime.toMillis() / 2)
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val clientIp = call.request.clientIp(trustedProxyIps)
        val now = Instant.now()
        val calldetails = "${call.request.httpMethod.value} ${call.request.uri}"

        var blocked = false
        var retryAfterSeconds = config.resetTime.toSeconds()
        rateLimitCache.compute(clientIp) { _, existing ->
            val state = existing ?: ClientRateState(id = clientIp, resetAt = now + config.resetTime)
            when {
                now.isAfter(state.resetAt) -> ClientRateState(clientIp, 1, now + config.resetTime)
                state.requests >= config.limit -> {
                    blocked = true
                    retryAfterSeconds = Duration.between(now, state.resetAt).toSeconds().coerceAtLeast(1L)
                    state
                }
                else -> state.copy(requests = state.requests + 1)
            }
        }

        if (blocked) {
            val contextInfo = ipLookup?.getContextForIp(clientIp)?.let { ctx ->
                val devices = ctx.devices.sorted().take(MAX_DEVICES_IN_LOG).map { it.shortId() }
                "accounts=${ctx.accounts.size} devices=$devices"
            } ?: "accounts=? (no auth seen)"
            log(TAG, WARN) { "Rate limits exceeded by ip=$clientIp $contextInfo -- $calldetails" }
            call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, "Rate limit exceeded. Try again later.")
            finish()
            return@intercept
        }
    }
}
