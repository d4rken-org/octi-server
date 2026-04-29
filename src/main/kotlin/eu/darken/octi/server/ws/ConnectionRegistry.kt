package eu.darken.octi.server.ws

import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.DeviceId
import eu.darken.octi.server.device.DeviceKey
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRegistry(
    private val appScope: AppScope,
    private val config: Config = Config(),
) {

    @Inject constructor(appScope: AppScope) : this(appScope, Config())

    data class Config(
        val maxPerAccount: Int = 64,
        val maxPerIp: Int = 32,
        val maxGlobal: Int = 10_000,
    )

    data class DeviceSession(
        val deviceId: DeviceId,
        val accountId: AccountId,
        val clientIp: String,
        val sessionId: UUID = UUID.randomUUID(),
        val outbox: Channel<String> = Channel(Channel.BUFFERED),
        val connectedAt: Instant = Instant.now(),
    )

    sealed interface RegisterResult {
        data class Accepted(val session: DeviceSession) : RegisterResult
        data class Rejected(val reason: String) : RegisterResult
    }

    data class Stats(
        val totalDevices: Int,
        val totalAccounts: Int,
    )

    private val sessions = ConcurrentHashMap<DeviceKey, DeviceSession>()
    private val registrationMutex = Mutex()

    init {
        appScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupStaleSessions()
            }
        }
    }

    suspend fun register(deviceId: DeviceId, accountId: AccountId, clientIp: String): RegisterResult = registrationMutex.withLock {
        val key = DeviceKey(accountId, deviceId)
        val isReconnect = sessions.containsKey(key)

        // Global limit (exclude self on reconnect)
        val effectiveSize = if (isReconnect) sessions.size - 1 else sessions.size
        if (effectiveSize >= config.maxGlobal) {
            log(TAG, WARN) { "register(): Global connection limit reached (${config.maxGlobal}), rejecting device=$deviceId" }
            return@withLock RegisterResult.Rejected("Server connection limit reached")
        }

        // Per-IP limit (exclude self on reconnect)
        val ipCount = sessions.values.count { it.clientIp == clientIp && it.deviceId != deviceId }
        if (ipCount >= config.maxPerIp) {
            log(TAG, WARN) { "register(): Per-IP limit reached (${config.maxPerIp}) for ip=$clientIp, rejecting device=$deviceId" }
            return@withLock RegisterResult.Rejected("Too many connections from this IP")
        }

        // Per-account limit — evict oldest if exceeded (exclude self on reconnect)
        val accountSessions = sessions.values.filter { it.accountId == accountId && it.deviceId != deviceId }
        val excessCount = accountSessions.size - config.maxPerAccount + 1
        if (excessCount > 0) {
            accountSessions.sortedBy { it.connectedAt }.take(excessCount).forEach { oldest ->
                removeIfCurrent(oldest)
                log(TAG, WARN) { "register(): Per-account limit (${config.maxPerAccount}), evicted device=${oldest.deviceId} for account=$accountId" }
            }
        }

        val deviceSession = DeviceSession(deviceId, accountId, clientIp)
        val old = sessions.put(key, deviceSession)
        if (old != null) {
            old.outbox.close()
            log(TAG, WARN) { "register(): Evicted existing session for device=$deviceId" }
        }
        val stats = stats()
        log(TAG, INFO) { "register(): device=$deviceId, account=$accountId | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
        RegisterResult.Accepted(deviceSession)
    }

    suspend fun unregister(session: DeviceSession) = registrationMutex.withLock {
        val removed = removeIfCurrent(session)
        val stats = stats()
        if (removed) {
            log(TAG, INFO) { "unregister(): device=${session.deviceId} | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
        } else {
            log(TAG, INFO) { "unregister(): device=${session.deviceId} session already replaced, skipping" }
        }
    }

    private fun removeIfCurrent(session: DeviceSession): Boolean {
        val removed = sessions.remove(DeviceKey(session.accountId, session.deviceId), session)
        if (removed) session.outbox.close()
        return removed
    }

    fun getAccountSessions(accountId: AccountId): Collection<DeviceSession> {
        return sessions.values.filter { it.accountId == accountId }
    }

    fun getAccountPeers(accountId: AccountId, excludeDevice: DeviceId): Collection<DeviceSession> {
        return sessions.values.filter { it.accountId == accountId && it.deviceId != excludeDevice }
    }

    fun activeDeviceKeys(): Set<DeviceKey> = sessions.keys.toSet()

    fun stats(): Stats = Stats(
        totalDevices = sessions.size,
        totalAccounts = sessions.values.map { it.accountId }.distinct().size,
    )

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun cleanupStaleSessions() = registrationMutex.withLock {
        val stale = sessions.values.filter { session ->
            session.outbox.isClosedForSend
        }
        if (stale.isNotEmpty()) {
            log(TAG, WARN) { "Cleaning up ${stale.size} stale sessions" }
            stale.forEach { removeIfCurrent(it) }
        }
        val stats = stats()
        log(TAG) { "Cleanup done | connections: ${stats.totalDevices} devices, ${stats.totalAccounts} accounts" }
    }

    companion object {
        private const val CLEANUP_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        private val TAG = logTag("WS", "ConnectionRegistry")
    }
}
