package eu.darken.octi.server.account

import eu.darken.octi.server.account.share.ShareRepo
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.headerDeviceId
import eu.darken.octi.server.common.normalizeLabel
import eu.darken.octi.server.common.verifyCaller
import eu.darken.octi.server.device.DeviceLimitExceededException
import eu.darken.octi.server.device.DeviceRepo
import eu.darken.octi.server.device.deviceCredentials
import eu.darken.octi.server.module.ModuleLifecycleService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRoute @Inject constructor(
    private val accountRepo: AccountRepo,
    private val deviceRepo: DeviceRepo,
    private val shareRepo: ShareRepo,
    private val lifecycleService: ModuleLifecycleService,
) {

    fun setup(rootRoute: Routing) {
        rootRoute.route("/v1/account") {
            post { create() }
            delete { delete() }
        }
    }

    private suspend fun RoutingContext.create() {
        val deviceId = call.headerDeviceId
        val shareCode = call.request.queryParameters["share"]

        log(TAG) { "create(): deviceId=${deviceId?.shortId()}, shareCode=${shareCode != null}" }

        if (deviceId == null) {
            log(TAG, WARN) { "create(): Missing header ID" }
            call.respond(HttpStatusCode.BadRequest, "X-Device-ID header is missing")
            return
        }

        // Check if this device is already registered (policy: one account per device for now)
        if (deviceRepo.isDeviceKnownGlobally(deviceId)) {
            log(TAG, WARN) { "create(${deviceId.shortId()}): Device is already known" }
            call.respond(HttpStatusCode.BadRequest, "Device is already registered")
            return
        }

        if (deviceCredentials != null) {
            log(TAG, WARN) { "create(${deviceId.shortId()}): Credentials were unexpectedly provided" }
            call.respond(HttpStatusCode.BadRequest, "Don't provide credentials during action creation or linking")
            return
        }

        val share = shareCode?.let {
            shareRepo.consumeShare(it) ?: run {
                log(TAG, WARN) { "create(${deviceId.shortId()}): Could not consume ShareCode" }
                call.respond(HttpStatusCode.Forbidden, "Invalid ShareCode")
                return
            }
        }

        val account = if (share != null) {
            log(TAG, INFO) { "create(${deviceId.shortId()}): Share valid, adding device" }
            val resolved = accountRepo.getAccount(share.accountId)
            if (resolved == null) {
                log(TAG, ERROR) { "create(${deviceId.shortId()}): Account ${share.accountId} disappeared, restoring share" }
                shareRepo.restoreShare(share)
                call.respond(HttpStatusCode.Forbidden, "Account no longer exists")
                return
            }
            resolved
        } else {
            log(TAG, INFO) { "create(${deviceId.shortId()}): Creating new account" }
            accountRepo.createAccount()
        }

        val device = try {
            deviceRepo.createDevice(
                deviceId = deviceId,
                account = account,
                version = call.request.headers["Octi-Device-Version"] ?: call.request.headers["User-Agent"],
                platform = call.request.headers["Octi-Device-Platform"],
                label = normalizeLabel(call.request.headers["Octi-Device-Label"]),
            )
        } catch (e: DeviceLimitExceededException) {
            if (share != null) {
                log(TAG, INFO) { "create(${deviceId.shortId()}): Device limit exceeded, restoring share" }
                shareRepo.restoreShare(share)
            }
            call.respond(HttpStatusCode.Conflict, "Device limit reached (max ${e.limit} per account)")
            return
        } catch (e: Exception) {
            if (share != null) {
                log(TAG, ERROR) { "create(${deviceId.shortId()}): Device creation failed, restoring share: ${e.asLog()}" }
                shareRepo.restoreShare(share)
            }
            throw e
        }

        val response = RegisterResponse(
            accountID = device.accountId,
            password = device.password,
        )
        call.respond(response).also {
            log(TAG, INFO) { "create(${deviceId.shortId()}): Device registered to ${account.id.shortId()}" }
        }
    }

    private suspend fun RoutingContext.delete() {
        val callerDevice = verifyCaller(TAG, deviceRepo) ?: return
        log(TAG, INFO) { "delete(${callerDevice.id.shortId()}): Deleting account ${callerDevice.accountId.shortId()}" }

        withContext(NonCancellable) {
            // Abort sessions and release quota before deleting data
            lifecycleService.deleteForAccount(callerDevice.accountId)
            deviceRepo.deleteDevices(callerDevice.accountId)
            shareRepo.removeSharesForAccount(callerDevice.accountId)
            accountRepo.deleteAccounts(listOf(callerDevice.accountId))
        }

        call.respond(HttpStatusCode.OK).also {
            log(TAG, INFO) { "delete(${callerDevice.id.shortId()}): Account deleted: ${callerDevice.accountId.shortId()}" }
        }
    }

    companion object {
        private val TAG = logTag("Account", "Route")
    }
}
