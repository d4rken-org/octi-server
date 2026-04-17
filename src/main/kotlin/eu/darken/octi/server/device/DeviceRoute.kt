package eu.darken.octi.server.device

import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.verifyCaller
import eu.darken.octi.server.module.ModuleLifecycleService
import eu.darken.octi.server.module.ModuleRepo
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRoute @Inject constructor(
    private val deviceRepo: DeviceRepo,
    private val moduleRepo: ModuleRepo,
    private val lifecycleService: ModuleLifecycleService,
) {

    fun setup(rootRoute: Routing) {
        rootRoute.route("/v1/devices") {
            get {
                try {
                    getDevices()
                } catch (e: Exception) {
                    log(TAG, ERROR) { "getDevices() failed: ${e.asLog()}" }
                    call.respond(HttpStatusCode.InternalServerError, "Failed to list devices")
                }
            }
            delete("/{deviceId}") {
                val deviceId: DeviceId? = try {
                    call.parameters["deviceId"]?.let { UUID.fromString(it) }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid device ID format")
                    return@delete
                }
                if (deviceId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing deviceId")
                    return@delete
                }
                try {
                    deleteDevice(deviceId)
                } catch (e: Exception) {
                    log(TAG, ERROR) { "deleteDevice($deviceId) failed: ${e.asLog()}" }
                    call.respond(HttpStatusCode.InternalServerError, "Failed to delete device")
                }
            }
            post("/reset") {
                try {
                    resetDevices()
                } catch (e: Exception) {
                    log(TAG, ERROR) { "resetDevices() failed: ${e.asLog()}" }
                    call.respond(HttpStatusCode.InternalServerError, "Failed to reset devices")
                }
            }
        }
    }

    private suspend fun RoutingContext.getDevices() {
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return

        val devices = deviceRepo.getDevices(callerDevice.accountId)
        val response = DevicesResponse(
            devices = devices.map {
                DevicesResponse.Device(
                    id = it.id,
                    version = it.version,
                    platform = it.platform,
                    label = it.label,
                    addedAt = it.addedAt,
                    lastSeen = it.lastSeen,
                )
            }
        )
        call.respond(response).also {
            log(TAG) { "getDevices(${callerDevice.id.shortId()}): ${devices.size} devices ${devices.map { it.id.shortId() }}" }
        }
    }

    private suspend fun RoutingContext.deleteDevice(deviceId: DeviceId) {
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        val targetKey = DeviceKey(callerDevice.accountId, deviceId)
        val targetDevice = deviceRepo.getDevice(targetKey)
        if (targetDevice == null) {
            call.respond(HttpStatusCode.NotFound, "Device not found $deviceId")
            return
        }

        lifecycleService.deleteForDevice(callerDevice.accountId, targetDevice)
        deviceRepo.deleteDevice(targetKey)

        call.respond(HttpStatusCode.OK).also {
            log(TAG, INFO) { "delete(${callerDevice.id.shortId()}): Device deleted: ${deviceId.shortId()}" }
        }
    }

    private suspend fun RoutingContext.resetDevices() {
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        val requestedTargets = call.receive<ResetRequest>().targets

        val targetDevices: Set<Device> = if (requestedTargets.isEmpty()) {
            log(TAG) { "No explicit targets provided, targeting all devices of this account." }
            deviceRepo.getDevices(callerDevice.accountId).toSet()
        } else {
            val resolved = mutableSetOf<Device>()
            for (id in requestedTargets) {
                val device = deviceRepo.getDevice(DeviceKey(callerDevice.accountId, id))
                if (device == null) {
                    call.respond(HttpStatusCode.NotFound, "Device not found: $id")
                    return
                }
                resolved.add(device)
            }
            resolved
        }

        log(TAG, INFO) { "resetDevices(${callerDevice.id.shortId()}): Resetting ${targetDevices.size} devices" }

        for (target in targetDevices) {
            lifecycleService.deleteForDevice(callerDevice.accountId, target)
        }

        call.respond(HttpStatusCode.OK).also {
            log(TAG, INFO) { "resetDevices(${callerDevice.id.shortId()}): Devices were reset" }
        }
    }

    companion object {
        private val TAG = logTag("Devices", "Route")
    }
}