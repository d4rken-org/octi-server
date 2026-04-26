package eu.darken.octi.server.account.share

import eu.darken.octi.server.App
import eu.darken.octi.server.account.Account
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountRepo
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.launchPeriodicJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*


@Singleton
class ShareRepo @Inject constructor(
    appScope: AppScope,
    private val serializer: Json,
    private val accountsRepo: AccountRepo,
    private val config: App.Config,
) {

    private val shares = ConcurrentHashMap<ShareId, Share>()
    private val mutex = Mutex()

    init {
        runBlocking {
            accountsRepo.getAccounts()
                .asSequence()
                .mapNotNull { account ->
                    try {
                        account.path.resolve(SHARES_DIR)
                            .takeIf { it.exists() }
                            ?.listDirectoryEntries()
                            ?.takeIf { it.isNotEmpty() }
                            ?.map { account to it }
                            ?.toList()
                            ?.also { log(TAG) { "Loading ${it.size} shares from account ${account.id}" } }
                    } catch (e: IOException) {
                        log(TAG, ERROR) { "Failed to list shares for $account\n${e.asLog()}" }
                        null
                    }
                }
                .flatten()
                .forEach { (account, path) ->
                    val data = try {
                        serializer.decodeFromString<Share.Data>(path.readText())
                    } catch (e: IOException) {
                        log(TAG, ERROR) { "Failed to read share $path: ${e.asLog()}" }
                        return@forEach
                    }
                    log(TAG) { "Share info loaded: $data" }
                    shares[data.id] = Share(
                        data = data,
                        path = path,
                        accountId = account.id,
                    )
                }
            log(TAG, INFO) { "${shares.size} shares loaded into memory" }
        }

        appScope.launchPeriodicJob(
            tag = TAG,
            interval = Duration.ofMillis((config.shareExpiration.toMillis() / 2).coerceAtLeast(1L)),
            initialDelay = Duration.ZERO,
            onErrorMessage = "Share expiration failed",
        ) {
            val now = Instant.now()
            val expiredShares = shares.filterValues { share ->
                Duration.between(share.createdAt, now) > config.shareExpiration
            }
            if (expiredShares.isNotEmpty()) {
                log(TAG, INFO) { "Deleting ${expiredShares.size} expired shares" }
                removeShares(expiredShares.map { it.value.id })
            }
        }

        appScope.launchPeriodicJob(
            tag = TAG,
            interval = config.shareGCInterval,
            initialDelay = Duration.ofMillis(config.shareGCInterval.toMillis() / 10),
            onErrorMessage = "Share cleanup failed",
        ) {
            val staleShares = shares.values.filter { !it.path.exists() }
            if (staleShares.isNotEmpty()) {
                log(TAG, INFO) { "Removing ${staleShares.size} stale shares" }
                removeShares(staleShares.map { it.id })
            }
        }
    }

    suspend fun createShare(account: Account): Share = mutex.withLock {
        log(TAG) { "createShare(${account.id}): Creating share..." }

        val data = Share.Data()
        val share = Share(
            data = data,
            path = account.path.resolve("shares/${data.id}.json"),
            accountId = account.id,
        )
        if (shares.containsKey(share.id)) throw IllegalStateException("Share ID collision???")

        share.path.run {
            if (!parent.exists()) {
                Files.createDirectory(parent)
                log(TAG) { "createShare(${account.id}): Parent created for $this" }
            }
            writeText(serializer.encodeToString(share.data))
            log(TAG, VERBOSE) { "createShare(${account.id}): Written to $this" }
        }
        shares[share.id] = share
        share.also { log(TAG) { "createShare(${account.id}): Share created created: $it" } }
    }

    suspend fun getShare(code: ShareCode): Share? = mutex.withLock {
        log(TAG, VERBOSE) { "getShare($code)" }
        shares.values.find { it.code == code }
    }

    suspend fun consumeShare(code: ShareCode): Share? = mutex.withLock {
        log(TAG, VERBOSE) { "consumeShare($code)" }
        val share = shares.values.find { it.code == code } ?: return@withLock null
        shares.remove(share.id)
        share.path.deleteIfExists()
        log(TAG) { "Share was consumed: $share" }
        share
    }

    suspend fun removeShares(ids: Collection<ShareId>) = mutex.withLock {
        log(TAG) { "removeShares($ids)..." }
        val toRemove = ids.mapNotNull { shares.remove(it) }
        log(TAG) { "removeShares($ids): Deleting ${toRemove.size} shares" }
        toRemove.forEach {
            it.path.deleteIfExists()
            log(TAG, VERBOSE) { "removeShares($ids): Share deleted $it" }
        }
    }

    suspend fun restoreShare(share: Share) = mutex.withLock {
        share.path.run {
            if (!parent.exists()) Files.createDirectory(parent)
            writeText(serializer.encodeToString(share.data))
        }
        shares[share.id] = share
        log(TAG) { "restoreShare(${share.id}): Share restored" }
    }

    suspend fun removeSharesForAccount(accountId: AccountId) {
        log(TAG) { "removeSharesForAccount($accountId)..." }
        val toRemove = shares.filter { it.value.accountId == accountId }.map { it.key }
        log(TAG) { "removeSharesForAccount($accountId): Deleting ${toRemove.size} shares" }
        removeShares(toRemove)
    }

    companion object {
        private const val SHARES_DIR = "shares"
        private val TAG = logTag("Account", "Share", "Repo")
    }
}
