package eu.darken.octi.server

import eu.darken.octi.server.account.AccountRoute
import eu.darken.octi.server.account.AccountStorageRoute
import eu.darken.octi.server.account.share.ShareRoute
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.Logging.Priority.INFO
import eu.darken.octi.server.common.debug.logging.Logging.Priority.WARN
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.AccountRateLimiter
import eu.darken.octi.server.common.AccountRateLimiterKey
import eu.darken.octi.server.common.IpDeviceTracker
import eu.darken.octi.server.common.IpDeviceTrackerKey
import eu.darken.octi.server.common.TrustedProxyIpsKey
import eu.darken.octi.server.common.installCallLogging
import eu.darken.octi.server.common.installRateLimit
import io.ktor.server.plugins.bodylimit.*
import eu.darken.octi.server.device.DeviceRoute
import eu.darken.octi.server.module.BlobRoute
import eu.darken.octi.server.module.ModuleRoute
import eu.darken.octi.server.myip.MyIpRoute
import eu.darken.octi.server.status.StatusRoute
import eu.darken.octi.server.ws.WsRoute
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
// ConditionalHeaders not used globally — see comment in server setup
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

class Server @Inject constructor(
    private val config: App.Config,
    private val statusRoute: StatusRoute,
    private val accountRoute: AccountRoute,
    private val shareRoute: ShareRoute,
    private val deviceRoute: DeviceRoute,
    private val moduleRoute: ModuleRoute,
    private val blobRoute: BlobRoute,
    private val accountStorageRoute: AccountStorageRoute,
    private val myIpRoute: MyIpRoute,
    private val wsRoute: WsRoute,
    private val serializers: SerializersModule,
    private val ipDeviceTracker: IpDeviceTracker,
    private val accountRateLimiter: AccountRateLimiter,
) {

    @Suppress("ExtractKtorModule")
    private val server by lazy {
        embeddedServer(Netty, config.port) {
            installCallLogging(config.trustedProxyIps)
            install(AutoHeadResponse)
            // PartialContent is NOT installed globally — BlobRoute.downloadBlob handles
            // Range / If-Range / Last-Modified manually because BlobHandle wraps an
            // already-open InputStream (POSIX inode-survival across concurrent commit
            // orphan-deletes). LocalFileContent + PartialContent would re-open at
            // response time, breaking that invariant.
            // ConditionalHeaders is NOT installed globally — the module commit path
            // handles If-Match/If-None-Match explicitly for optimistic concurrency control.
            // Installing it globally would cause Ktor to intercept these headers before
            // the route handler, returning 412 based on response ETag mismatches.
            install(WebSockets) {
                pingPeriod = 30.seconds
                timeout = 60.seconds
                maxFrameSize = 4096
            }

            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    serializersModule = serializers
                })
            }

            install(StatusPages) {
                exception<CancellationException> { _, cause -> throw cause }
                exception<BadRequestException> { call, cause ->
                    log(TAG, WARN) { "Bad request: ${cause.message}" }
                    if (!call.response.isCommitted) {
                        call.respond(HttpStatusCode.BadRequest, "Bad request")
                    }
                }
                exception<PayloadTooLargeException> { call, _ ->
                    if (!call.response.isCommitted) {
                        call.respond(HttpStatusCode.PayloadTooLarge)
                    }
                }
                exception<Throwable> { call, cause ->
                    if (cause is Error) throw cause
                    log(TAG, ERROR) { "Unhandled exception: ${cause.asLog()}" }
                    if (!call.response.isCommitted) {
                        call.respond(HttpStatusCode.InternalServerError, "Internal server error")
                    }
                }
            }

            attributes.put(IpDeviceTrackerKey, ipDeviceTracker)
            attributes.put(AccountRateLimiterKey, accountRateLimiter)
            attributes.put(TrustedProxyIpsKey, config.trustedProxyIps)

            config.rateLimit
                ?.let { installRateLimit(it, ipDeviceTracker, config.trustedProxyIps) }
                ?: log(TAG, WARN) { "rateLimit is not configured" }

            routing {
                // Default body limit for all routes. Individual routes can override
                // by installing RequestBodyLimit on a child route() block.
                config.payloadLimit?.let { limit ->
                    install(RequestBodyLimit) { bodyLimit { limit } }
                }
                statusRoute.setup(this)
                accountRoute.setup(this)
                accountStorageRoute.setup(this)
                shareRoute.setup(this)
                deviceRoute.setup(this)
                moduleRoute.setup(this)
                blobRoute.setup(this)
                wsRoute.setup(this)
                myIpRoute.setup(this)
            }
        }
    }
    private var isRunning = false

    fun start() {
        log(TAG, INFO) { "Server is starting..." }
        server.monitor.apply {
            subscribe(ApplicationStarted) {
                log(TAG, INFO) { "Server is ready" }
                isRunning = true
            }
            subscribe(ApplicationStopping) {
                log(TAG, INFO) { "Server is stopping..." }
                isRunning = false
            }
        }
        server.start(wait = true)
    }

    fun stop() {
        log(TAG, INFO) { "Server is stopping..." }
        server.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        log(TAG, INFO) { "Server stopped" }
    }

    fun isRunning(): Boolean = isRunning

    companion object {
        private val TAG = logTag("Server")
    }
}
