package eu.darken.octi.server.common

import eu.darken.octi.server.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.octi.server.common.debug.logging.RequestId
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import io.ktor.server.application.*
import io.ktor.server.application.ApplicationCallPipeline.ApplicationPhase.Plugins
import io.ktor.server.request.*
import kotlinx.coroutines.withContext

private val TAG = logTag("HTTP")

fun Application.installCallLogging(trustedProxyIps: Set<String> = IpHelper.DEFAULT_TRUSTED_PROXY_IPS) {
    intercept(Plugins) {
        val rid = RequestId.generate()
        withContext(RequestId.contextElement(rid)) {
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val userAgent = call.request.userAgent() ?: "Unknown"
            val ip = call.request.clientIp(trustedProxyIps)
            val isWebSocket = call.request.headers["Upgrade"]?.equals("websocket", ignoreCase = true) == true
            val start = System.currentTimeMillis()

            try {
                proceed()
            } finally {
                val status = call.response.status()?.value ?: "aborted"
                if (isWebSocket) {
                    log(TAG, VERBOSE) { "$ip($userAgent): $method $path -> $status (WS)" }
                } else {
                    val duration = System.currentTimeMillis() - start
                    log(TAG, VERBOSE) { "$ip($userAgent): $method $path -> $status (${duration}ms)" }
                }
            }
        }
    }
}
