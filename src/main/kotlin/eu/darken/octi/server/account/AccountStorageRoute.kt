package eu.darken.octi.server.account

import eu.darken.octi.server.App
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.verifyCaller
import eu.darken.octi.server.device.DeviceRepo
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountStorageRoute @Inject constructor(
    private val config: App.Config,
    private val deviceRepo: DeviceRepo,
    private val storageTracker: AccountStorageTracker,
) {

    @Serializable
    data class StorageResponse(
        val storageApiVersion: Int,
        val accountQuotaBytes: Long,
        val usedBytes: Long,
        val reservedBytes: Long,
        val availableBytes: Long,
        val maxBlobBytes: Long,
        val maxModuleDocumentBytes: Long,
        val maxActiveUploadSessionsPerDevice: Int,
        val idleSessionTtlSeconds: Long,
        val absoluteSessionTtlSeconds: Long,
    )

    fun setup(rootRoute: Routing) {
        rootRoute.get("/v1/account/storage") {
            try {
                getStorage()
            } catch (e: Exception) {
                log(TAG, ERROR) { "getStorage() failed: ${e.asLog()}" }
                call.respond(HttpStatusCode.InternalServerError, "Storage query failed")
            }
        }
    }

    private suspend fun RoutingContext.getStorage() {
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return

        val usage = storageTracker.getUsage(callerDevice.accountId)
        val available = maxOf(0, config.accountQuotaBytes - usage.usedBytes - usage.reservedBytes)

        call.respond(
            StorageResponse(
                storageApiVersion = 1,
                accountQuotaBytes = config.accountQuotaBytes,
                usedBytes = usage.usedBytes,
                reservedBytes = usage.reservedBytes,
                availableBytes = available,
                maxBlobBytes = config.maxBlobBytes,
                maxModuleDocumentBytes = config.maxModuleDocumentBytes,
                maxActiveUploadSessionsPerDevice = config.maxActiveUploadSessionsPerDevice,
                idleSessionTtlSeconds = config.idleSessionTtlSeconds,
                absoluteSessionTtlSeconds = config.absoluteSessionTtlSeconds,
            )
        ).also {
            log(TAG) { "getStorage(${callerDevice.id.shortId()}): used=${usage.usedBytes}, reserved=${usage.reservedBytes}, available=$available" }
        }
    }

    companion object {
        private val TAG = logTag("Account", "Storage", "Route")
    }
}
