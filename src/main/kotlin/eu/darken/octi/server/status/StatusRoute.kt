package eu.darken.octi.server.status

import eu.darken.octi.server.account.AccountRepo
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.module.UploadSessionMeta
import eu.darken.octi.server.module.UploadSessionRepo
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatusRoute @Inject constructor(
    private val accountRepo: AccountRepo,
    private val deviceRepo: DeviceRepo,
    private val storageTracker: AccountStorageTracker,
    private val sessionRepo: UploadSessionRepo,
) {

    private val startedAt = Instant.now()

    @Serializable
    data class StatusResponse(
        val status: String,
    )

    @Serializable
    data class MetricsResponse(
        val uptimeSeconds: Long,
        val accounts: Int,
        val devices: Int,
        val storageUsedBytes: Long,
        val storageReservedBytes: Long,
        val uploadSessionsActive: Int,
        val uploadSessionsComplete: Int,
    )

    fun setup(rootRoute: Routing) {
        rootRoute.get("/v1/status") {
            call.respond(HttpStatusCode.OK, StatusResponse(status = "ok"))
        }
        rootRoute.get("/v1/metrics") {
            val usage = storageTracker.aggregateUsage()
            val sessionCounts = sessionRepo.sessionCountsByState()
            call.respond(
                HttpStatusCode.OK,
                MetricsResponse(
                    uptimeSeconds = java.time.Duration.between(startedAt, Instant.now()).toSeconds(),
                    accounts = accountRepo.getAccounts().size,
                    devices = deviceRepo.allDevices().size,
                    storageUsedBytes = usage.usedBytes,
                    storageReservedBytes = usage.reservedBytes,
                    uploadSessionsActive = sessionCounts[UploadSessionMeta.State.ACTIVE] ?: 0,
                    uploadSessionsComplete = sessionCounts[UploadSessionMeta.State.COMPLETE] ?: 0,
                )
            )
        }
    }
}
