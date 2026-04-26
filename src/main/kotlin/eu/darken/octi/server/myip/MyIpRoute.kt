package eu.darken.octi.server.myip

import eu.darken.octi.server.App
import eu.darken.octi.server.common.clientIp
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MyIpRoute @Inject constructor(
    private val config: App.Config,
) {

    fun setup(rootRoute: Routing) {
        rootRoute.get("/v1/myip") {
            val clientIp = call.request.clientIp(config.trustedProxyIps)
            call.respond(HttpStatusCode.OK, mapOf("ip" to clientIp))
        }
    }
}
