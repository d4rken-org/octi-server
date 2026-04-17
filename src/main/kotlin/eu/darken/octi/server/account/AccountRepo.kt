package eu.darken.octi.server.account

import eu.darken.octi.server.App
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.DeviceRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@Singleton
class AccountRepo @Inject constructor(
    private val config: App.Config,
    private val serializer: Json,
    private val appScope: AppScope,
) {
    private val accountsPath = config.dataPath.resolve("accounts").apply {
        if (!exists()) {
            Files.createDirectories(this)
            log(TAG) { "Created $this" }
        }
    }
    private val accounts = ConcurrentHashMap<UUID, Account>()
    private val mutex = Mutex()

    init {
        runBlocking {
            Files.newDirectoryStream(accountsPath).use { stream ->
                stream.asSequence()
                    .filter {
                        log(TAG, VERBOSE) { "Reading $it" }
                        if (it.isDirectory()) {
                            true
                        } else {
                            log(TAG, WARN) { "Not a directory: $it" }
                            false
                        }
                    }
                    .map { it to it.resolve(ACC_FILENAME) }
                    .forEach { (accDir, configPath) ->
                        if (configPath.exists()) {
                            val accData = try {
                                serializer.decodeFromString<Account.Data>(configPath.readText())
                            } catch (e: IOException) {
                                log(TAG, ERROR) { "Failed to read $accDir: ${e.asLog()}" }
                                return@forEach
                            }
                            log(TAG) { "Account info loaded: $accData" }
                            accounts[accData.id] = Account(
                                data = accData,
                                path = accDir,
                            )
                        } else {
                            log(TAG, WARN) { "Missing account config for $accDir, cleaning up..." }
                            accDir.deleteRecursively()
                        }
                    }
            }

            log(TAG, INFO) { "${accounts.size} accounts loaded into memory" }
        }

        appScope.launch(Dispatchers.IO) {
            delay((config.accountGCInterval.toMillis() / 10))
            while (currentCoroutineContext().isActive) {
                val now = Instant.now()
                val orphaned = accounts.filterValues {
                    // We don't lock the mutex, skip accounts that are currently in creation
                    if (Duration.between(it.createdAt, now) < config.accountGCInterval) {
                        return@filterValues false
                    }
                    it.path.resolve(DeviceRepo.DEVICES_DIR).listDirectoryEntries().isEmpty()
                }
                if (orphaned.isNotEmpty()) {
                    log(TAG, INFO) { "Deleting ${orphaned.size} accounts without devices" }
                    deleteAccounts(orphaned.map { it.value.id })
                }
                delay(config.accountGCInterval.toMillis())
            }
        }
    }

    suspend fun createAccount(): Account = mutex.withLock {
        log(TAG) { "createAccount(): Creating account..." }

        val accData = Account.Data()
        if (accounts.containsKey(accData.id)) throw IllegalStateException("Account ID collision???")

        val account = Account(
            data = accData,
            path = accountsPath.resolve(accData.id.toString()),
        )

        account.path.run {
            if (!exists()) {
                createDirectory()
                log(TAG) { "createAccount(): Dirs created: $this" }
            }
            resolve(ACC_FILENAME).writeText(serializer.encodeToString(accData))
            log(TAG, VERBOSE) { "createAccount(): Account written to $this" }
        }
        accounts[accData.id] = account
        account.also { log(TAG) { "createAccount(): Account created: $accData" } }
    }

    suspend fun getAccount(id: AccountId): Account? {
        return accounts[id].also { log(TAG, VERBOSE) { "getAccount($id) -> $it" } }
    }

    fun getAccounts(): List<Account> {
        return accounts.values.toList()
    }

    suspend fun deleteAccounts(ids: Collection<AccountId>) {
        log(TAG) { "deleteAccount($ids)..." }
        val accounts = mutex.withLock {
            ids.map { accounts.remove(it) ?: throw IllegalArgumentException("Unknown account") }
        }
        accounts.forEach { account ->
            val deleted = try {
                account.path.deleteRecursively()
                true
            } catch (e: IOException) {
                log(TAG, ERROR) { "Failed to delete account directory: $account: ${e.asLog()}" }
                false
            }
            if (!deleted) {
                val accountConfig = account.path.resolve(ACC_FILENAME)
                if (accountConfig.deleteIfExists()) {
                    log(TAG, WARN) { "Deleted account file, will clean up on next restart." }
                }
            }
            log(TAG) { "deleteAccount($ids): Account deleted: $account" }
        }
    }

    companion object {
        private const val ACC_FILENAME = "account.json"
        private val TAG = logTag("Account", "Repo")
    }
}