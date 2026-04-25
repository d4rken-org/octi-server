package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Coverage for the per-device module count cap (G1 / P2.2). Each enforcement site
 * (legacy POST, PUT commit, blob session create) should reject the (cap + 1)th
 * distinct moduleId with 409 Conflict, but writes to an existing moduleId should
 * always pass regardless of the cap.
 */
class ModuleLimitFlowTest : TestRunner() {

    private fun base64Encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    @Test
    fun `legacy POST creating a new module past the cap returns 409`() = runTest2(
        appConfig = baseConfig.copy(maxModulesPerDevice = 2),
    ) {
        val creds = createDevice()

        writeModule(creds, "eu.darken.octi.first", data = "x").status shouldBe HttpStatusCode.OK
        writeModule(creds, "eu.darken.octi.second", data = "x").status shouldBe HttpStatusCode.OK
        writeModule(creds, "eu.darken.octi.third", data = "x").status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `legacy POST overwriting an existing module is unaffected by the cap`() = runTest2(
        appConfig = baseConfig.copy(maxModulesPerDevice = 1),
    ) {
        val creds = createDevice()

        writeModule(creds, "eu.darken.octi.only", data = "x").status shouldBe HttpStatusCode.OK
        // Same moduleId — must not 409 even though we're at the cap.
        writeModule(creds, "eu.darken.octi.only", data = "yy").status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `PUT commit creating a new module past the cap returns 409`() = runTest2(
        appConfig = baseConfig.copy(maxModulesPerDevice = 1),
    ) {
        val creds = createDevice()

        // Take the cap.
        writeModule(creds, "eu.darken.octi.taken", data = "x").status shouldBe HttpStatusCode.OK

        // PUT commit for a new moduleId — should 409 before any disk I/O.
        http.put("/v1/module/eu.darken.octi.fresh") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "${base64Encode("hi".toByteArray())}", "blobRefs": []}""")
        }.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `blob session create for a new module past the cap returns 409`() = runTest2(
        appConfig = baseConfig.copy(maxModulesPerDevice = 1),
    ) {
        val creds = createDevice()

        writeModule(creds, "eu.darken.octi.taken", data = "x").status shouldBe HttpStatusCode.OK

        http.post("/v1/module/eu.darken.octi.fresh/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 100}""")
        }.status shouldBe HttpStatusCode.Conflict
    }

    @Test
    fun `blob session create for an already-existing module is unaffected by the cap`() = runTest2(
        appConfig = baseConfig.copy(maxModulesPerDevice = 1),
    ) {
        val creds = createDevice()

        writeModule(creds, "eu.darken.octi.taken", data = "x").status shouldBe HttpStatusCode.OK

        // Same moduleId already has a directory on disk; cap shouldn't bite.
        http.post("/v1/module/eu.darken.octi.taken/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": 100}""")
        }.status shouldBe HttpStatusCode.Created
    }
}
