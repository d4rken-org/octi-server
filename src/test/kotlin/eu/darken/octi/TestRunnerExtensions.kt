package eu.darken.octi

import eu.darken.octi.TestRunner.TestEnvironment
import eu.darken.octi.server.common.serialization.UUIDSerializer
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.*

suspend fun HttpResponse.asMap() = Json.decodeFromString<Map<String, String>>(bodyAsText())

@Serializable
data class Auth(
    val account: String,
    val password: String
)

data class Credentials(
    val deviceId: UUID,
    val auth: Auth,
) {
    val account: String
        get() = auth.account
    val password: String
        get() = auth.password
}

suspend fun HttpResponse.asAuth() = Json.decodeFromString<Auth>(bodyAsText())

fun Auth.toBearerToken(): String {
    val credentials = "$account:$password"
    val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
    return "Basic $encodedCredentials"
}

fun HttpRequestBuilder.addDeviceId(id: UUID) {
    headers {
        append("X-Device-ID", id.toString())
    }
}

fun HttpRequestBuilder.addAuth(auth: Auth) {
    headers {
        append("Authorization", auth.toBearerToken())
    }
}

fun HttpRequestBuilder.addCredentials(credentials: Credentials) {
    addDeviceId(credentials.deviceId)
    addAuth(credentials.auth)
}

suspend fun TestEnvironment.createDeviceRaw(
    deviceId: UUID = UUID.randomUUID(),
    shareCode: String? = null,
    version: String? = null,
    platform: String? = null,
    label: String? = null,
    userAgent: String? = null,
): HttpResponse = this.http.run {
    post {
        url {
            takeFrom("/v1/account")
            if (shareCode != null) parameters.append("share", shareCode)
        }
        addDeviceId(deviceId)
        if (version != null) headers.append("Octi-Device-Version", version)
        if (platform != null) headers.append("Octi-Device-Platform", platform)
        if (label != null) headers.append("Octi-Device-Label", label)
        if (userAgent != null) headers.set(HttpHeaders.UserAgent, userAgent)
    }
}

suspend fun TestEnvironment.createDevice(
    deviceId: UUID = UUID.randomUUID(),
    shareCode: String? = null,
    version: String? = null,
    platform: String? = null,
    label: String? = null,
    userAgent: String? = null,
): Credentials {
    val credentials = createDeviceRaw(deviceId, shareCode, version, platform, label, userAgent).asAuth()
    return Credentials(deviceId, credentials)
}

suspend fun TestEnvironment.createDevice(
    credentials: Credentials,
): Credentials {
    val shareCode = createShareCode(credentials)

    return createDevice(shareCode = shareCode)
}

suspend fun TestEnvironment.createShareCode(
    credentials: Credentials
): String = http.run {
    val shareCode = post("/v1/account/share") {
        addDeviceId(credentials.deviceId)
        addAuth(credentials.auth)
    }.asMap()["code"]!!

    return shareCode
}

@Serializable
data class TestDevices(
    val devices: Set<Device>,
) {
    @Serializable
    data class Device(
        @Serializable(with = UUIDSerializer::class) val id: UUID,
        val version: String? = "ktor-client",
        val platform: String? = null,
        val label: String? = null,
        val addedAt: String? = null,
        val lastSeen: String? = null,
    )
}

suspend fun TestEnvironment.getDevicesRaw(
    credentials: Credentials
): HttpResponse = this.http.run {
    http.get("/v1/devices") {
        addCredentials(credentials)
    }
}

suspend fun TestEnvironment.getDevices(
    credentials: Credentials
): TestDevices = this.http.run {
    val response = getDevicesRaw(credentials)
    Json.decodeFromString<TestDevices>(response.bodyAsText())
}

suspend fun TestEnvironment.deleteAccount(
    credentials: Credentials,
) = this.http.run {
    delete("/v1/account") {
        addCredentials(credentials)
    }
}

suspend fun TestEnvironment.deleteDevice(
    credentials: Credentials,
    target: UUID = credentials.deviceId,
) = this.http.run {
    delete("/v1/devices/$target") {
        addCredentials(credentials)
    }.apply {
        status shouldBe HttpStatusCode.OK
    }
}

fun HttpRequestBuilder.targetModule(moduleId: String, device: UUID?) {
    url {
        takeFrom("/v1/module/$moduleId")
        if (device != null) parameters.append("device-id", device.toString())
    }
}

suspend fun TestEnvironment.readModuleRaw(
    creds: Credentials,
    moduleId: String,
    deviceId: UUID? = creds.deviceId,
) = http.get {
    targetModule(moduleId, deviceId)
    addCredentials(creds)
}

suspend fun TestEnvironment.readModule(
    creds: Credentials,
    moduleId: String,
    deviceId: UUID? = creds.deviceId,
) = http.get {
    targetModule(moduleId, deviceId)
    addCredentials(creds)
}.bodyAsText()

suspend fun TestEnvironment.writeModule(
    creds: Credentials,
    moduleId: String,
    deviceId: UUID? = creds.deviceId,
    data: String,
) = http.post {
    targetModule(moduleId, deviceId)
    addCredentials(creds)
    contentType(ContentType.Application.OctetStream)
    setBody(data)
}

suspend fun TestEnvironment.deleteModuleRaw(
    creds: Credentials,
    moduleId: String,
    deviceId: UUID? = creds.deviceId,
) = http.delete {
    targetModule(moduleId, deviceId)
    addCredentials(creds)
}
