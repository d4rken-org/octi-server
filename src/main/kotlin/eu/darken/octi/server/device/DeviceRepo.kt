package eu.darken.octi.server.device

import eu.darken.octi.server.App
import eu.darken.octi.server.account.Account
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountRepo
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.launchPeriodicJob
import eu.darken.octi.server.module.ModuleLifecycleService
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@Singleton
class DeviceRepo @Inject constructor(
    appScope: AppScope,
    private val config: App.Config,
    private val serializer: Json,
    private val accountsRepo: AccountRepo,
    // Lazy: ModuleLifecycleService transitively depends on DeviceRepo via ModuleRepo,
    // so a direct injection would be a Dagger cycle.
    private val lifecycleService: dagger.Lazy<ModuleLifecycleService>,
) {

    private val devices = ConcurrentHashMap<DeviceKey, Device>()
    private val lastSeenPersistedAt = ConcurrentHashMap<DeviceKey, Instant>()
    private val deletingDevices = mutableMapOf<DeviceKey, PendingDeviceDeletion>()
    private val deletingDeviceIds = mutableMapOf<DeviceId, PendingDeviceDeletion>()
    private val mutex = Mutex()

    init {
        runBlocking {
            accountsRepo.getAccounts()
                .asSequence()
                .mapNotNull { account ->
                    try {
                        account.path.resolve(DEVICES_DIR)
                            .listDirectoryEntries()
                            .map { account to it }
                            .also { log(TAG, VERBOSE) { "Listing ${it.size} device(s) for account ${account.id}" } }
                    } catch (e: IOException) {
                        log(TAG, ERROR) { "Failed to list devices for $account" }
                        null
                    }
                }
                .flatten()
                .forEach { (account, deviceDir) ->
                    log(TAG, VERBOSE) { "Reading $deviceDir" }
                    val deviceData = try {
                        serializer.decodeFromString<Device.Data>(deviceDir.resolve(DEVICE_FILENAME).readText())
                    } catch (e: Exception) {
                        log(TAG, ERROR) { "Failed to read $deviceDir: ${e.asLog()}" }
                        return@forEach
                    }
                    log(TAG) { "Device info loaded: $deviceData" }
                    val device = Device(
                        data = deviceData,
                        path = deviceDir,
                        accountId = account.id,
                    )
                    devices[device.key] = device
                    lastSeenPersistedAt[device.key] = device.lastSeen
                }
            log(TAG, INFO) { "${devices.size} devices loaded into memory" }
        }
        appScope.launchPeriodicJob(
            tag = TAG,
            interval = config.deviceGCInterval,
            initialDelay = Duration.ofMillis(config.deviceGCInterval.toMillis() / 10),
            onErrorMessage = "Device cleanup failed",
        ) {
            val now = Instant.now()
            val staleKeys = devices.entries
                .filter { Duration.between(it.value.lastSeen, now) >= config.deviceExpiration }
                .map { it.key }

            for (key in staleKeys) {
                try {
                    log(TAG, WARN) { "Deleting stale device $key" }
                    // Route through lifecycle service first so module bytes are credited
                    // back to the account quota and outstanding sessions are aborted.
                    // Direct deleteDevice(key) bypasses both.
                    val target = devices[key]
                    if (target != null) {
                        lifecycleService.get().deleteForDevice(key.accountId, target)
                    }
                    deleteDevice(key)
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to delete stale device $key: ${e.message}" }
                }
            }
        }
    }

    fun allDevices(): Collection<Device> = devices.values.toList()

    private fun Device.writeDevice() {
        path.resolve(DEVICE_FILENAME).writeText(serializer.encodeToString(data))
    }

    private data class PendingDeviceDeletion(
        val device: Device,
        val completed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private fun trackPendingDeletion(device: Device): PendingDeviceDeletion {
        val pendingDeletion = PendingDeviceDeletion(device)
        deletingDevices[device.key] = pendingDeletion
        deletingDeviceIds[device.id] = pendingDeletion
        return pendingDeletion
    }

    private suspend fun finishPendingDeletion(pendingDeletion: PendingDeviceDeletion) {
        withContext(NonCancellable) {
            mutex.withLock {
                deletingDevices.remove(pendingDeletion.device.key, pendingDeletion)
                deletingDeviceIds.remove(pendingDeletion.device.id, pendingDeletion)
            }
            pendingDeletion.completed.complete(Unit)
        }
    }

    suspend fun createDevice(
        account: Account,
        deviceId: DeviceId,
        version: String?,
        platform: String? = null,
        label: String? = null,
    ): Device {
        val data = Device.Data(
            id = deviceId,
            version = version,
            platform = platform,
            label = label,
        )
        val device = Device(
            data = data,
            accountId = account.id,
            path = account.path.resolve("$DEVICES_DIR/${data.id}")
        )
        while (true) {
            val pendingDeletion = mutex.withLock {
                val pendingDeletion = deletingDevices[device.key] ?: deletingDeviceIds[device.id]
                if (pendingDeletion != null) {
                    pendingDeletion
                } else {
                    if (devices[device.key] != null) throw IllegalStateException("Device already exists: ${device.key}")
                    if (devices.values.any { it.id == device.id }) {
                        throw IllegalStateException("Device ID already registered to another account: ${device.id}")
                    }
                    // Count cap is enforced under the same mutex that registers the new device,
                    // so two concurrent creates can't both pass the check.
                    val currentDeviceCount = devices.values.count { it.accountId == account.id }
                    if (currentDeviceCount >= config.maxDevicesPerAccount) {
                        throw DeviceLimitExceededException(config.maxDevicesPerAccount)
                    }

                    device.path.run {
                        if (!parent.exists()) {
                            parent.createDirectory()
                            log(TAG) { "Created parent dir for $this" }
                        }
                        if (!exists()) {
                            createDirectory()
                            log(TAG) { "Created dir for $this" }
                        }
                    }
                    device.writeDevice()
                    lastSeenPersistedAt[device.key] = device.lastSeen
                    log(TAG, VERBOSE) { "Device written: $this" }
                    devices[device.key] = device
                    log(TAG) { "createDevice(): Device created $device" }
                    return device
                }
            }
            pendingDeletion.completed.await()
        }
    }

    suspend fun getDevice(key: DeviceKey): Device? {
        return devices[key]
    }

    fun isDeviceKnownGlobally(deviceId: DeviceId): Boolean {
        return devices.values.any { it.id == deviceId }
    }

    enum class MissingDeviceReason(val tag: String) {
        UNKNOWN_ACCOUNT("unknown-account"),
        DEVICE_ACCOUNT_MISMATCH("device-account-mismatch"),
        UNKNOWN_DEVICE("unknown-device"),
    }

    suspend fun classifyMissingDevice(key: DeviceKey): MissingDeviceReason {
        if (accountsRepo.getAccount(key.accountId) == null) {
            return MissingDeviceReason.UNKNOWN_ACCOUNT
        }

        return if (devices.values.any { it.id == key.deviceId && it.accountId != key.accountId }) {
            MissingDeviceReason.DEVICE_ACCOUNT_MISMATCH
        } else {
            MissingDeviceReason.UNKNOWN_DEVICE
        }
    }

    suspend fun getDevices(accountId: AccountId): Collection<Device> {
        val accountDevices = mutableSetOf<Device>()
        devices.forEach {
            if (it.value.accountId == accountId) {
                accountDevices.add(it.value)
            }
        }
        return accountDevices
    }

    suspend fun deleteDevice(key: DeviceKey) {
        log(TAG, VERBOSE) { "deleteDevice($key)..." }
        val pendingDeletion = mutex.withLock {
            val toDelete = devices.remove(key).also { lastSeenPersistedAt.remove(key) }
                ?: throw IllegalArgumentException("$key not found")
            trackPendingDeletion(toDelete)
        }
        try {
            pendingDeletion.device.sync.withLock {
                val toDelete = pendingDeletion.device
                toDelete.path.deleteRecursively()
                log(TAG) { "deleteDevice($key): Device deleted: $toDelete" }
            }
        } finally {
            finishPendingDeletion(pendingDeletion)
        }
    }

    suspend fun deleteDevices(accountId: AccountId) {
        log(TAG, VERBOSE) { "deleteDevices($accountId)..." }
        val pendingDeletions = mutex.withLock {
            devices
                .filter { it.value.accountId == accountId }
                .map {
                    lastSeenPersistedAt.remove(it.key)
                    trackPendingDeletion(devices.remove(it.key)!!)
                }
        }
        log(TAG) { "deleteDevices($accountId): Deleting ${pendingDeletions.size} devices" }
        var firstFailure: Throwable? = null
        pendingDeletions.forEach { pendingDeletion ->
            try {
                val device = pendingDeletion.device
                device.sync.withLock {
                    device.path.deleteRecursively()
                    log(TAG) { "deleteDevices($accountId): Device deleted: $device" }
                }
            } catch (t: Throwable) {
                if (firstFailure == null) {
                    firstFailure = t
                } else {
                    firstFailure.addSuppressed(t)
                }
            } finally {
                finishPendingDeletion(pendingDeletion)
            }
        }
        firstFailure?.let { throw it }
    }

    suspend fun updateDevice(key: DeviceKey, action: (Device.Data) -> Device.Data) {
        val device = mutex.withLock { devices[key] } ?: return
        device.sync.withLock {
            val current = mutex.withLock {
                devices[key]?.takeIf { it.sync === device.sync }
            } ?: return
            val newDevice = current.copy(data = action(current.data))
            val oldWithoutLastSeen = current.data.copy(lastSeen = newDevice.lastSeen)
            val metadataChanged = oldWithoutLastSeen != newDevice.data
            val lastPersisted = lastSeenPersistedAt[key] ?: current.lastSeen
            val shouldPersist = metadataChanged ||
                Duration.between(lastPersisted, newDevice.lastSeen) >= LAST_SEEN_DEBOUNCE
            if (shouldPersist) {
                newDevice.writeDevice()
            }
            mutex.withLock {
                if (devices[key]?.sync === device.sync) {
                    devices[key] = newDevice
                    if (shouldPersist) {
                        lastSeenPersistedAt[key] = newDevice.lastSeen
                    }
                }
            }
        }
    }

    companion object {
        const val DEVICES_DIR = "devices"
        private const val DEVICE_FILENAME = "device.json"
        private val LAST_SEEN_DEBOUNCE: Duration = Duration.ofSeconds(30)
        private val TAG = logTag("Device", "Repo")
    }
}

class DeviceLimitExceededException(val limit: Int) :
    RuntimeException("Device limit exceeded: max $limit per account")
