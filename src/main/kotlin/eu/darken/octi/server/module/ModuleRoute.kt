package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.parseStrongEtag
import eu.darken.octi.server.common.verifyCaller
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceId
import eu.darken.octi.server.device.DeviceKey
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.ws.SyncNotifier
import io.ktor.http.*
import io.ktor.server.http.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModuleRoute @Inject constructor(
    private val config: App.Config,
    private val deviceRepo: DeviceRepo,
    private val moduleRepo: ModuleRepo,
    private val lifecycleService: ModuleLifecycleService,
    private val syncNotifier: SyncNotifier,
) {

    @Serializable
    data class CommitRequest(
        val documentBase64: String,
        val blobRefs: List<CommitBlobRef> = emptyList(),
    )

    @Serializable
    data class CommitBlobRef(
        val blobId: String,
    )

    @Serializable
    data class CommitResponse(
        val etag: String,
    )

    private suspend fun RoutingContext.requireModuleId(): ModuleId? {
        val moduleId = call.parameters["moduleId"]
        if (moduleId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing moduleId")
            return null
        }
        if (moduleId.length > 1024) {
            call.respond(HttpStatusCode.BadRequest, "Invalid moduleId")
            return null
        }
        if (!MODULE_ID_REGEX.matches(moduleId)) {
            call.respond(HttpStatusCode.BadRequest, "Invalid moduleId")
            return null
        }
        return moduleId
    }

    private suspend fun RoutingContext.catchError(action: suspend RoutingContext.() -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log(TAG, ERROR) { "$call ${e.asLog()}" }
            call.respond(HttpStatusCode.InternalServerError, "Request failed")
        }
    }

    fun setup(rootRoute: Routing) {
        rootRoute.route("/v1/module") {
            get("/{moduleId}") { catchError { readModule() } }
            post("/{moduleId}") { catchError { writeModule() } }
            // PUT needs a higher body limit for base64-encoded documents
            route("/{moduleId}") {
                install(RequestBodyLimit) { bodyLimit { config.maxModuleDocumentBytes * 2 } }
                put { catchError { commitModule() } }
            }
            delete("/{moduleId}") { catchError { deleteModule() } }
        }
    }

    private suspend fun RoutingContext.verifyTarget(callerDevice: Device): Device? {
        val targetDeviceId: DeviceId? = try {
            call.request.queryParameters["device-id"]?.let { UUID.fromString(it) }
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, "Invalid device ID format")
            return null
        }
        if (targetDeviceId == null) {
            log(TAG, WARN) { "Caller did not supply target device: $callerDevice" }
            call.respond(HttpStatusCode.BadRequest, "Target device id not supplied")
            return null
        }
        val target = deviceRepo.getDevice(DeviceKey(callerDevice.accountId, targetDeviceId))
        if (target == null) {
            log(TAG, WARN) { "Target device was not found for $targetDeviceId" }
            call.respond(HttpStatusCode.NotFound, "Target device not found")
            return null
        }

        return target
    }

    private suspend fun RoutingContext.readModule() {
        val moduleId = requireModuleId() ?: return
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        val targetDevice = verifyTarget(callerDevice) ?: return

        val ref = moduleRepo.readRef(callerDevice, targetDevice, moduleId)
        val stream = ref.blobStream
        try {
            if (ref.modifiedAt == null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.response.header("X-Modified-At", ref.modifiedAt.toHttpDateString())
                if (ref.etag != null) {
                    call.response.header("ETag", "\"${ref.etag}\"")
                }
                if (stream != null) {
                    call.respondOutputStream(
                        contentType = ContentType.Application.OctetStream,
                        contentLength = ref.sizeBytes,
                    ) {
                        stream.use { it.copyTo(this) }
                    }
                } else {
                    call.respondBytes(ByteArray(0), contentType = ContentType.Application.OctetStream)
                }
            }.also {
                log(TAG) { "readModule(${callerDevice.id.shortId()}): ${ref.sizeBytes}B read from $moduleId" }
            }
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun RoutingContext.writeModule() {
        val moduleId = requireModuleId() ?: return
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        val targetDevice = verifyTarget(callerDevice) ?: return

        val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
        if (config.payloadLimit != null && contentLength != null && contentLength > config.payloadLimit) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        val payload = call.receive<ByteArray>()
        if (config.payloadLimit != null && payload.size > config.payloadLimit) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            return
        }

        val result = lifecycleService.legacyWrite(callerDevice, targetDevice, moduleId, Module.Write(payload))
        when (result) {
            is ModuleLifecycleService.LegacyWriteResult.BlobBacked -> {
                call.respond(HttpStatusCode.Conflict, "Module has external blob refs, use PUT commit instead")
                return
            }
            is ModuleLifecycleService.LegacyWriteResult.Success -> {
                call.response.header("ETag", "\"${result.meta.etag}\"")
                call.respond(HttpStatusCode.OK).also {
                    log(TAG) { "writeModule(${callerDevice.id.shortId()}): ${payload.size}B written to $moduleId" }
                }
            }
        }

        syncNotifier.enqueueModuleChanged(
            accountId = callerDevice.accountId,
            sourceDeviceId = callerDevice.id,
            targetDeviceId = targetDevice.id,
            moduleId = moduleId,
            action = "updated",
        )
    }

    private suspend fun RoutingContext.deleteModule() {
        val moduleId = requireModuleId() ?: return
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        val targetDevice = verifyTarget(callerDevice) ?: return

        lifecycleService.legacyDelete(callerDevice, targetDevice, moduleId)

        call.respond(HttpStatusCode.OK).also {
            log(TAG) { "deleteModule(${callerDevice.id.shortId()}): $moduleId deleted" }
        }

        syncNotifier.enqueueModuleChanged(
            accountId = callerDevice.accountId,
            sourceDeviceId = callerDevice.id,
            targetDeviceId = targetDevice.id,
            moduleId = moduleId,
            action = "deleted",
        )
    }

    private suspend fun RoutingContext.commitModule() {
        val moduleId = requireModuleId() ?: return
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        val targetDevice = verifyTarget(callerDevice) ?: return

        val request = call.receive<CommitRequest>()

        val documentBytes = try {
            Base64.getDecoder().decode(request.documentBase64)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, "Invalid base64 in documentBase64")
            return
        }

        if (documentBytes.size > config.maxModuleDocumentBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Document size exceeds maximum")
            return
        }

        val blobIdSet = request.blobRefs.map { it.blobId }.toSet()
        if (blobIdSet.size != request.blobRefs.size) {
            call.respond(HttpStatusCode.BadRequest, "Duplicate blobId values in blobRefs")
            return
        }

        val ifMatchRaw = call.request.headers["If-Match"]
        val ifNoneMatchRaw = call.request.headers["If-None-Match"]

        if (ifMatchRaw != null && ifNoneMatchRaw != null) {
            call.respond(HttpStatusCode.BadRequest, "Cannot use both If-Match and If-None-Match")
            return
        }

        val ifMatch = ifMatchRaw?.let {
            parseStrongEtag(it) ?: run {
                call.respond(HttpStatusCode.BadRequest, "Malformed If-Match header")
                return
            }
        }
        val ifNoneMatch = ifNoneMatchRaw?.let {
            parseStrongEtag(it) ?: run {
                call.respond(HttpStatusCode.BadRequest, "Malformed If-None-Match header")
                return
            }
        }

        val result = lifecycleService.commitModule(
            caller = callerDevice,
            target = targetDevice,
            moduleId = moduleId,
            documentBytes = documentBytes,
            blobRefIds = request.blobRefs.map { it.blobId },
            ifMatch = ifMatch,
            ifNoneMatch = ifNoneMatch,
        )

        when (result) {
            is ModuleLifecycleService.CommitResult.PreconditionFailed -> {
                call.respond(HttpStatusCode.PreconditionFailed, result.message)
                return
            }
            is ModuleLifecycleService.CommitResult.BadRequest -> {
                call.respond(HttpStatusCode.BadRequest, result.message)
                return
            }
            is ModuleLifecycleService.CommitResult.Success -> {
                call.response.header("ETag", "\"${result.etag}\"")
                call.respond(CommitResponse(etag = result.etag))
            }
        }

        syncNotifier.enqueueModuleChanged(
            accountId = callerDevice.accountId,
            sourceDeviceId = callerDevice.id,
            targetDeviceId = targetDevice.id,
            moduleId = moduleId,
            action = "updated",
        )
    }

    companion object {
        private val MODULE_ID_REGEX = "^[a-z]+(\\.[a-z0-9_]+)*$".toRegex()
        private val TAG = logTag("Module", "Route")
    }
}