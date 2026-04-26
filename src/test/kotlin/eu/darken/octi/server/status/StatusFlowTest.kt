package eu.darken.octi.server.status

import eu.darken.octi.TestRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class StatusFlowTest : TestRunner() {

    @Test
    fun `get status`() = runTest2 {
        http.get("/v1/status") {

        }.apply {
            status shouldBe HttpStatusCode.OK
        }
    }

    @Test
    fun `get public metrics returns coarse aggregate counters`() = runTest2 {
        http.get("/v1/metrics").apply {
            status shouldBe HttpStatusCode.OK
            val body = Json.parseToJsonElement(bodyAsText()).jsonObject
            body["accounts"] shouldNotBe null
            body["devices"] shouldNotBe null
            body["uploadSessionsActive"] shouldNotBe null
        }
    }
}
