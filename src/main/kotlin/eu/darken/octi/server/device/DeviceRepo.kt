package eu.darken.octi.server.device

import eu.darken.octi.server.App
import eu.darken.octi.server.account.Account
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountRepo
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {

    private val devices = ConcurrentHashMap<DeviceKey, Device>()
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
                }
            log(TAG, INFO) { "${devices.size} devices loaded into memory" }
        }
        appScope.launch(Dispatchers.IO) {
            delay(config.deviceGCInterval.toMillis() / 10)
            while (currentCoroutineContext().isActive) {
                val now = Instant.now()
                val staleKeys = devices.entries
                    .filter { Duration.between(it.value.lastSeen, now) >= config.deviceExpiration }
                    .map { it.key }

                for (key in staleKeys) {
                    try {
                        log(TAG, WARN) { "Deleting stale device $key" }
                        deleteDevice(key)
                    } catch (e: Exception) {
                        log(TAG, ERROR) { "Failed to delete stale device $key: ${e.message}" }
                    }
                }
                delay(config.deviceGCInterval.toMillis())
            }
        }
    }

    fun allDevices(): Collection<Device> = devices.values.toList()

    private fun Device.writeDevice() {
        path.resolve(DEVICE_FILENAME).writeText(serializer.encodeToString(data))
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
        mutex.withLock {
            if (devices[device.key] != null) throw IllegalStateException("Device already exists: ${device.key}")
            if (devices.values.any { it.id == device.id }) {
                throw IllegalStateException("Device ID already registered to another account: ${device.id}")
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
            log(TAG, VERBOSE) { "Device written: $this" }
            devices[device.key] = device
        }
        log(TAG) { "createDevice(): Device created $device" }
        return device
    }

    suspend fun getDevice(key: DeviceKey): Device? {
        return devices[key]
    }

    fun isDeviceKnownGlobally(deviceId: DeviceId): Boolean {
        return devices.values.any { it.id == deviceId }
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
        val toDelete = mutex.withLock {
            devices.remove(key) ?: throw IllegalArgumentException("$key not found")
        }
        toDelete.sync.withLock {
            toDelete.path.deleteRecursively()
            log(TAG) { "deleteDevice($key): Device deleted: $toDelete" }
        }
    }

    suspend fun deleteDevices(accountId: AccountId) {
        log(TAG, VERBOSE) { "deleteDevices($accountId)..." }
        val toDelete = mutex.withLock {
            devices
                .filter { it.value.accountId == accountId }
                .map { devices.remove(it.key)!! }
        }
        log(TAG) { "deleteDevices($accountId): Deleting ${toDelete.size} devices" }
        toDelete.forEach { device ->
            device.sync.withLock {
                device.path.deleteRecursively()
                log(TAG) { "deleteDevices($accountId): Device deleted: $device" }
            }
        }
    }

    suspend fun updateDevice(key: DeviceKey, action: (Device.Data) -> Device.Data) {
        val device = devices[key] ?: return
        device.sync.withLock {
            val newDevice = device.copy(data = action(device.data))
            newDevice.writeDevice()
            devices[key] = newDevice
        }
    }

    companion object {
        const val DEVICES_DIR = "devices"
        private const val DEVICE_FILENAME = "device.json"
        private val TAG = logTag("Device", "Repo")
    }
}