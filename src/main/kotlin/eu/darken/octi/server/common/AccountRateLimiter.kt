package eu.darken.octi.server.common

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import io.ktor.util.AttributeKey
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

val AccountRateLimiterKey = AttributeKey<AccountRateLimiter>("AccountRateLimiter")

/**
 * Per-account request bucket. Layered on top of the per-IP [installRateLimit] so an
 * authenticated account behind shared NAT doesn't get its quota burned by other tenants
 * on the same address. Only consulted after credential validation succeeds — see
 * [verifyCaller] for the call sequence (validate → rate-check → touch).
 */
@Singleton
class AccountRateLimiter @Inject constructor(
    private val config: App.Config,
) {

    private data class Bucket(val requests: Int, val resetAt: Instant)

    private val buckets = ConcurrentHashMap<AccountId, Bucket>()
    private val limit = config.accountRateLimit
    private val window: Duration = Duration.ofSeconds(config.accountRateLimitWindowSeconds)

    /**
     * Returns true if this call fits within the account's budget; false if the
     * account is over its limit and the request should be rejected with 429.
     * On accept, increments the counter atomically.
     */
    fun tryAcquire(accountId: AccountId): Boolean {
        val now = Instant.now()
        var accepted = true
        buckets.compute(accountId) { _, existing ->
            val bucket = existing
                ?.takeIf { now.isBefore(it.resetAt) }
                ?: Bucket(requests = 0, resetAt = now.plus(window))
            when {
                bucket.requests >= limit -> {
                    accepted = false
                    bucket
                }
                else -> bucket.copy(requests = bucket.requests + 1)
            }
        }
        if (!accepted) {
            log(TAG, VERBOSE) { "tryAcquire($accountId): over limit ($limit per ${window.seconds}s)" }
        }
        return accepted
    }

    companion object {
        private val TAG = logTag("Account", "RateLimiter")
    }
}
