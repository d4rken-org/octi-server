package eu.darken.octi.server.ws

import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncNotifier @Inject constructor(
    private val appScope: AppScope,
    private val connectionRegistry: ConnectionRegistry,
    private val json: Json,
) {

    @Serializable
    data class EventPayload(
        val events: List<Event>,
    ) {
        @Serializable
        sealed interface Event {
            @Serializable
            @SerialName("module_changed")
            data class ModuleChanged(
                val deviceId: String,
                val moduleId: String,
                val modifiedAt: String,
                val action: String,
                val sourceDeviceId: String? = null,
            ) : Event
        }
    }

    private data class PendingBroadcast(
        val accountId: AccountId,
        val events: MutableList<EventPayload.Event>,
        var job: Job,
    )

    private val lock = Mutex()
    private val pending = mutableMapOf<AccountId, PendingBroadcast>()

    suspend fun enqueueModuleChanged(
        accountId: AccountId,
        sourceDeviceId: DeviceId,
        targetDeviceId: DeviceId,
        moduleId: String,
        action: String = "updated",
    ) {
        val event = EventPayload.Event.ModuleChanged(
            deviceId = targetDeviceId.toString(),
            moduleId = moduleId,
            modifiedAt = Instant.now().toString(),
            action = action,
            sourceDeviceId = sourceDeviceId.toString(),
        )

        lock.withLock {
            val existing = pending[accountId]
            if (existing != null) {
                existing.events.add(event)
                existing.job.cancel()
                existing.job = launchBroadcast(accountId)
                log(TAG) { "enqueue(): Merged event for account=$accountId, pending=${existing.events.size}" }
            } else {
                val job = launchBroadcast(accountId)
                pending[accountId] = PendingBroadcast(
                    accountId = accountId,
                    events = mutableListOf(event),
                    job = job,
                )
                log(TAG) { "enqueue(): New batch for account=$accountId" }
            }
        }
    }

    private fun launchBroadcast(accountId: AccountId): Job = appScope.launch {
        try {
            delay(DEBOUNCE_MS)

            val broadcast = lock.withLock {
                pending.remove(accountId)
            } ?: return@launch

            val allPeers = connectionRegistry.getAccountSessions(accountId)

            if (allPeers.isEmpty()) {
                log(TAG, VERBOSE) { "broadcast(): No peers for account=$accountId, dropping ${broadcast.events.size} events" }
                return@launch
            }

            val moduleIds = broadcast.events.joinToString { (it as? EventPayload.Event.ModuleChanged)?.moduleId ?: "?" }
            log(TAG, INFO) { "broadcast(): Sending [$moduleIds] to account=$accountId" }

            allPeers.forEach { peer ->
                val peerDeviceIdStr = peer.deviceId.toString()
                val relevantEvents = broadcast.events.filter { event ->
                    when (event) {
                        is EventPayload.Event.ModuleChanged -> {
                            // Self-suppression: filter on sourceDeviceId (the actor), not deviceId (the target)
                            val source = event.sourceDeviceId ?: event.deviceId
                            source != peerDeviceIdStr
                        }
                    }
                }
                if (relevantEvents.isEmpty()) {
                    log(TAG) { "broadcast(): Skipping device=${peer.deviceId} (all events originated from this device)" }
                    return@forEach
                }

                val payload = json.encodeToString(EventPayload(events = relevantEvents))
                val result = peer.outbox.trySend(payload)
                if (result.isSuccess) {
                    log(TAG) { "broadcast(): Delivered ${relevantEvents.size} events to device=${peer.deviceId}" }
                } else if (result.isClosed) {
                    log(TAG, WARN) { "broadcast(): Session closed for device=${peer.deviceId}, skipping" }
                } else {
                    log(TAG, WARN) { "broadcast(): Buffer full for device=${peer.deviceId}, dropping notification" }
                }
            }
        } catch (e: CancellationException) {
            log(TAG) { "broadcast(): Debounce reset for account=$accountId" }
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "broadcast(): Failed for account=$accountId: ${e.message}" }
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
        private val TAG = logTag("WS", "SyncNotifier")
    }
}
