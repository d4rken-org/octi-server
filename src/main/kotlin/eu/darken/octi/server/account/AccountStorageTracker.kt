package eu.darken.octi.server.account

import eu.darken.octi.server.App
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountStorageTracker @Inject constructor(
    private val config: App.Config,
) {

    data class AccountUsage(
        val usedBytes: Long = 0,
        val reservedBytes: Long = 0,
    ) {
        val totalBytes: Long get() = usedBytes + reservedBytes
    }

    private val usageMap = ConcurrentHashMap<AccountId, AccountUsage>()
    // Use synchronized lock instead of Mutex — operations are fast in-memory updates,
    // and this avoids requiring suspend context for all callers.
    private val quotaLock = Any()

    fun getUsage(accountId: AccountId): AccountUsage {
        return usageMap.getOrDefault(accountId, AccountUsage())
    }

    /**
     * Rebuilds usage accounting for an account from startup recovery scan.
     */
    fun rebuildUsage(accountId: AccountId, usedBytes: Long, reservedBytes: Long) {
        synchronized(quotaLock) {
            usageMap[accountId] = AccountUsage(usedBytes = usedBytes, reservedBytes = reservedBytes)
            log(TAG, VERBOSE) { "rebuildUsage($accountId): usedBytes=$usedBytes, reservedBytes=$reservedBytes" }
        }
    }

    /**
     * Tries to reserve quota for an upload session.
     */
    fun tryReserve(accountId: AccountId, sizeBytes: Long): Boolean {
        return synchronized(quotaLock) {
            val current = usageMap.getOrDefault(accountId, AccountUsage())
            if (current.totalBytes + sizeBytes > config.accountQuotaBytes) {
                log(TAG) { "tryReserve($accountId): rejected, need=$sizeBytes, used=${current.usedBytes}, reserved=${current.reservedBytes}, quota=${config.accountQuotaBytes}" }
                false
            } else {
                usageMap[accountId] = current.copy(reservedBytes = current.reservedBytes + sizeBytes)
                log(TAG, VERBOSE) { "tryReserve($accountId): reserved=$sizeBytes, total=${current.totalBytes + sizeBytes}" }
                true
            }
        }
    }

    /**
     * Releases a reservation (session abort/expiry).
     */
    fun releaseReservation(accountId: AccountId, sizeBytes: Long) {
        synchronized(quotaLock) {
            val current = usageMap.getOrDefault(accountId, AccountUsage())
            usageMap[accountId] = current.copy(
                reservedBytes = maxOf(0, current.reservedBytes - sizeBytes)
            )
            log(TAG, VERBOSE) { "releaseReservation($accountId): released=$sizeBytes" }
        }
    }

    /**
     * Commits reserved bytes to used bytes (session -> live blob).
     */
    fun commitReservation(accountId: AccountId, reservedBytes: Long, orphanedBytes: Long) {
        synchronized(quotaLock) {
            val current = usageMap.getOrDefault(accountId, AccountUsage())
            usageMap[accountId] = current.copy(
                usedBytes = maxOf(0, current.usedBytes + reservedBytes - orphanedBytes),
                reservedBytes = maxOf(0, current.reservedBytes - reservedBytes),
            )
            log(TAG, VERBOSE) { "commitReservation($accountId): committed=$reservedBytes, orphaned=$orphanedBytes" }
        }
    }

    /**
     * Adjusts used bytes directly (e.g., legacy write/delete).
     */
    fun adjustUsed(accountId: AccountId, delta: Long) {
        synchronized(quotaLock) {
            val current = usageMap.getOrDefault(accountId, AccountUsage())
            usageMap[accountId] = current.copy(
                usedBytes = maxOf(0, current.usedBytes + delta)
            )
        }
    }

    fun removeAccount(accountId: AccountId) {
        usageMap.remove(accountId)
    }

    companion object {
        private val TAG = logTag("Account", "StorageTracker")
    }
}
