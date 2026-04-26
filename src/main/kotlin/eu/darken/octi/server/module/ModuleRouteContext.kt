package eu.darken.octi.server.module

import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.verifyCaller
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceId
import eu.darken.octi.server.device.DeviceKey
import eu.darken.octi.server.device.DeviceRepo
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import java.util.UUID

data class ModuleCallContext(
    val moduleId: ModuleId,
    val caller: Device,
    val target: Device,
)

suspend fun RoutingContext.resolveModuleContext(
    tag: String,
    deviceRepo: DeviceRepo,
): ModuleCallContext? {
    val moduleId = requireRouteModuleId() ?: return null
    val caller = verifyCaller(tag, deviceRepo) ?: return null
    val target = verifyRouteTarget(tag, deviceRepo, caller) ?: return null
    return ModuleCallContext(moduleId, caller, target)
}

suspend fun RoutingContext.requireRouteModuleId(): ModuleId? {
    val moduleId = call.parameters["moduleId"]
    if (moduleId == null) {
        call.respond(HttpStatusCode.BadRequest, "Missing moduleId")
        return null
    }
    if (moduleId.length > 1024 || !MODULE_ID_REGEX.matches(moduleId)) {
        call.respond(HttpStatusCode.BadRequest, "Invalid moduleId")
        return null
    }
    return moduleId
}

suspend fun RoutingContext.verifyRouteTarget(
    tag: String,
    deviceRepo: DeviceRepo,
    callerDevice: Device,
): Device? {
    val targetDeviceId: DeviceId? = try {
        call.request.queryParameters["device-id"]?.let { UUID.fromString(it) }
    } catch (e: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "Invalid device ID format")
        return null
    }
    if (targetDeviceId == null) {
        log(tag, WARN) { "Caller ${callerDevice.id.shortId()} did not supply target device" }
        call.respond(HttpStatusCode.BadRequest, "Target device id not supplied")
        return null
    }
    val target = deviceRepo.getDevice(DeviceKey(callerDevice.accountId, targetDeviceId))
    if (target == null) {
        log(tag, WARN) { "Target device was not found for $targetDeviceId" }
        call.respond(HttpStatusCode.NotFound, "Target device not found")
        return null
    }
    return target
}

private val MODULE_ID_REGEX = "^[a-z]+(\\.[a-z0-9_]+)*$".toRegex()
