package eu.darken.octi.server.device

import eu.darken.octi.*
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.io.path.exists

class DeviceFlowTest : TestRunner() {

    private val endPoint = "/v1/devices"

    @Test
    fun `get devices`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        val devices = getDevices(creds1).devices
        devices.size shouldBe 2
        devices.map { it.id }.toSet() shouldBe setOf(creds1.deviceId, creds2.deviceId)
        devices.forEach { device ->
            device.version.shouldNotBeNull()
            device.addedAt.shouldNotBeNull()
            device.lastSeen.shouldNotBeNull()
        }
    }

    @Test
    fun `get devices - requires valid auth`() = runTest2 {
        val creds1 = createDevice()
        http.get(endPoint) {
            addDeviceId(creds1.deviceId)
        }.apply {
            status shouldBe HttpStatusCode.BadRequest
        }
    }

    @Test
    fun `deleting ourselves`() = runTest2 {
        val creds1 = createDevice()
        deleteDevice(creds1)
        http.get(endPoint) {
            addCredentials(creds1)
        }.apply {
            status shouldBe HttpStatusCode.NotFound
        }
    }

    @Test
    fun `delete other device`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        getDevices(creds1).devices.size shouldBe 2

        deleteDevice(creds1, creds2.deviceId)

        getDevices(creds1).devices.size shouldBe 1
    }

    @Test
    fun `delete devices - requires valid auth`() = runTest2 {
        val creds1 = createDevice()
        http.delete("$endPoint/${UUID.randomUUID()}") {
            addDeviceId(creds1.deviceId)
        }.apply {
            status shouldBe HttpStatusCode.BadRequest
        }
    }

    @Test
    fun `deleting device wipes module data`() = runTest2 {
        val creds1 = createDevice()

        getModulesPath(creds1).exists() shouldBe false
        writeModule(creds1, "abc", data = "test")
        getModulesPath(creds1).exists() shouldBe true

        deleteDevice(creds1)
        getModulesPath(creds1).exists() shouldBe false
    }

    @Test
    fun `resetting devices`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        writeModule(creds1, "abc", data = "test")
        writeModule(creds2, "abc", data = "test")
        http.post("$endPoint/reset") {
            addCredentials(creds1)
            contentType(ContentType.Application.Json)
            setBody(setOf(creds1.deviceId.toString(), creds2.deviceId.toString()))
            setBody("{targets: [${creds1.deviceId}, ${creds2.deviceId}]}")
        }
        readModule(creds1, "abc") shouldBe ""
        readModule(creds2, "abc") shouldBe ""
    }

    @Test
    fun `deleting device from different account returns 404`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice()

        http.delete("$endPoint/${creds2.deviceId}") {
            addCredentials(creds1)
        }.apply {
            status shouldBe HttpStatusCode.NotFound
            bodyAsText() shouldContain "Device not found"
        }
    }

    @Test
    fun `reset with cross-account device ID returns 404`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice()

        http.post("$endPoint/reset") {
            addCredentials(creds1)
            contentType(ContentType.Application.Json)
            setBody("""{"targets": ["${creds2.deviceId}"]}""")
        }.apply {
            status shouldBe HttpStatusCode.NotFound
            bodyAsText() shouldContain "Device not found"
        }
    }

    @Test
    fun `version from Octi-Device-Version at registration`() = runTest2 {
        val creds = createDevice(version = "octi/1.0.0")
        val device = getDevices(creds).devices.single()
        device.version shouldBe "octi/1.0.0"
    }

    @Test
    fun `version falls back to User-Agent`() = runTest2 {
        val creds = createDevice()
        val device = getDevices(creds).devices.single()
        device.version shouldBe "ktor-client"
    }

    @Test
    fun `version updates on authenticated request`() = runTest2 {
        val creds = createDevice()
        getDevices(creds).devices.single().version shouldBe "ktor-client"

        http.get("/v1/devices") {
            addCredentials(creds)
            headers.append("Octi-Device-Version", "octi/2.0.0")
        }

        getDevices(creds).devices.single().version shouldBe "octi/2.0.0"
    }

    @Test
    fun `version not overwritten without header`() = runTest2 {
        val creds = createDevice(version = "octi/1.0.0")
        getDevices(creds).devices.single().version shouldBe "octi/1.0.0"

        http.get("/v1/devices") {
            addCredentials(creds)
        }

        getDevices(creds).devices.single().version shouldBe "octi/1.0.0"
    }

    @Test
    fun `debug user agent does not replace device version at registration`() = runTest2 {
        val userAgent = "octi/1.0.0-beta0/FOSS/dev-d0a81273"
        val creds = createDevice(version = "1.0.0-beta0", userAgent = userAgent)
        val device = getDevices(creds).devices.single()
        val key = DeviceKey(UUID.fromString(creds.account), creds.deviceId)

        device.version shouldBe "1.0.0-beta0"
        component.deviceClientIdentityTracker().userAgentFor(key) shouldBe userAgent
    }

    @Test
    fun `debug user agent tracked on authenticated request without replacing version`() = runTest2 {
        val userAgent = "octi/1.0.0-beta0/GPLAY/dev-d0a81273"
        val creds = createDevice(version = "1.0.0-beta0")
        val key = DeviceKey(UUID.fromString(creds.account), creds.deviceId)

        http.get("/v1/devices") {
            addCredentials(creds)
            headers.append("Octi-Device-Version", "1.0.0-beta0")
            headers.set(HttpHeaders.UserAgent, userAgent)
        }

        getDevices(creds).devices.single().version shouldBe "1.0.0-beta0"
        component.deviceClientIdentityTracker().userAgentFor(key) shouldBe userAgent
    }

    @Test
    fun `platform stored at registration`() = runTest2 {
        val creds = createDevice(platform = "android")
        val device = getDevices(creds).devices.single()
        device.platform shouldBe "android"
    }

    @Test
    fun `label stored at registration`() = runTest2 {
        val creds = createDevice(label = "My Work Phone")
        val device = getDevices(creds).devices.single()
        device.label shouldBe "My Work Phone"
    }

    @Test
    fun `label updates on authenticated request`() = runTest2 {
        val creds = createDevice()
        getDevices(creds).devices.single().label shouldBe null

        http.get("/v1/devices") {
            addCredentials(creds)
            headers.append("Octi-Device-Label", "Pixel 8 Pro")
        }

        getDevices(creds).devices.single().label shouldBe "Pixel 8 Pro"
    }

    @Test
    fun `label not overwritten without header`() = runTest2 {
        val creds = createDevice(label = "My Phone")
        getDevices(creds).devices.single().label shouldBe "My Phone"

        http.get("/v1/devices") {
            addCredentials(creds)
        }

        getDevices(creds).devices.single().label shouldBe "My Phone"
    }

    @Test
    fun `label truncated to 128 chars`() = runTest2 {
        val longLabel = "A".repeat(200)
        val creds = createDevice(label = longLabel)
        val device = getDevices(creds).devices.single()
        device.label shouldBe "A".repeat(128)
    }

    @Test
    fun `blank label treated as absent on update`() = runTest2 {
        val creds = createDevice(label = "My Phone")
        getDevices(creds).devices.single().label shouldBe "My Phone"

        http.get("/v1/devices") {
            addCredentials(creds)
            headers.append("Octi-Device-Label", "   ")
        }

        getDevices(creds).devices.single().label shouldBe "My Phone"
    }

    @Test
    fun `blank label treated as absent at registration`() = runTest2 {
        val creds = createDevice(label = "   ")
        getDevices(creds).devices.single().label shouldBe null
    }

    @Test
    fun `platform updates on authenticated request`() = runTest2 {
        val creds = createDevice()
        getDevices(creds).devices.single().platform shouldBe null

        http.get("/v1/devices") {
            addCredentials(creds)
            headers.append("Octi-Device-Platform", "desktop")
        }

        getDevices(creds).devices.single().platform shouldBe "desktop"
    }

    @Test
    fun `auth failure with missing X-Device-ID is tracked`() = runTest2 {
        http.get(endPoint).apply {
            status shouldBe HttpStatusCode.BadRequest
        }

        val failures = component.deviceClientIdentityTracker().snapshotAuthFailures()
        failures.size shouldBe 1
        failures[0].reasonTag shouldBe "missing-device-id"
        failures[0].userAgent shouldBe "ktor-client"
    }

    @Test
    fun `auth failure with missing credentials is tracked`() = runTest2 {
        val creds = createDevice()

        http.get(endPoint) {
            addDeviceId(creds.deviceId)
        }.apply {
            status shouldBe HttpStatusCode.BadRequest
        }

        val failures = component.deviceClientIdentityTracker().snapshotAuthFailures()
        failures.any { it.reasonTag == "missing-credentials" } shouldBe true
    }

    @Test
    fun `auth failure with unknown device is tracked`() = runTest2 {
        val creds = createDevice()

        http.get(endPoint) {
            addDeviceId(UUID.randomUUID())
            addAuth(creds.auth)
        }.apply {
            status shouldBe HttpStatusCode.NotFound
        }

        val failures = component.deviceClientIdentityTracker().snapshotAuthFailures()
        failures.any { it.reasonTag == "unknown-device" } shouldBe true
    }

    @Test
    fun `auth failure with bad password is tracked`() = runTest2 {
        val creds = createDevice()

        http.get(endPoint) {
            addDeviceId(creds.deviceId)
            addAuth(Auth(creds.account, "wrong-password"))
        }.apply {
            status shouldBe HttpStatusCode.Unauthorized
        }

        val failures = component.deviceClientIdentityTracker().snapshotAuthFailures()
        failures.any { it.reasonTag == "bad-credentials" } shouldBe true
    }

    @Test
    fun `resetting devices without specific targets`() = runTest2 {
        val creds1 = createDevice()
        val creds2 = createDevice(creds1)
        writeModule(creds1, "abc", data = "test")
        writeModule(creds2, "abc", data = "test")
        http.post("$endPoint/reset") {
            addCredentials(creds1)
            contentType(ContentType.Application.Json)
            setBody("{targets: []}")
        }
        readModule(creds1, "abc") shouldBe ""
        readModule(creds2, "abc") shouldBe ""
    }
}
