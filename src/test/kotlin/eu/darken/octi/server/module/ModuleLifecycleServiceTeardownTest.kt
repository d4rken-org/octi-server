package eu.darken.octi.server.module

import eu.darken.octi.*
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.io.path.exists

/**
 * Verifies that DELETE /v1/devices/{id} and DELETE /v1/account cascade correctly through
 * [ModuleLifecycleService] — committed blobs release quota, active sessions release their
 * reservation, on-disk dirs are removed, and a later startup-recovery pass sees nothing.
 */
class ModuleLifecycleServiceTeardownTest : TestRunner() {

    private val moduleA = "eu.darken.octi.teardown.a"
    private val moduleB = "eu.darken.octi.teardown.b"

    @Serializable
    private data class SessionInfo(
        val blobId: String = "",
        val sessionId: String = "",
        val state: String = "",
    )

    @Serializable
    private data class FinalizeInfo(val blobId: String = "")

    // Register → upload → finalize → commit. Returns the blob's storage size.
    private suspend fun TestEnvironment.commitBlobAndReturnSize(
        creds: Credentials,
        moduleId: String,
        bytes: ByteArray,
    ): Long {
        val hash = bytes.sha256Hex()
        val session = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": ${bytes.size}, "hashAlgorithm": "sha256", "hashHex": "$hash"}""")
        }.body<SessionInfo>()
        http.patch("/v1/module/$moduleId/blob-sessions/${session.sessionId}") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("Upload-Offset", "0")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        http.post("/v1/module/$moduleId/blob-sessions/${session.sessionId}/finalize") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        http.put("/v1/module/$moduleId") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            header("If-None-Match", "*")
            contentType(ContentType.Application.Json)
            setBody("""{"documentBase64": "", "blobRefs": [{"blobId": "${session.blobId}"}]}""")
        }
        return bytes.size.toLong()
    }

    private suspend fun TestEnvironment.startActiveSession(
        creds: Credentials,
        moduleId: String,
        sizeBytes: Long,
    ): String {
        val body = http.post("/v1/module/$moduleId/blob-sessions") {
            url { parameters.append("device-id", creds.deviceId.toString()) }
            addCredentials(creds)
            contentType(ContentType.Application.Json)
            setBody("""{"sizeBytes": $sizeBytes}""")
        }.body<SessionInfo>()
        return body.sessionId
    }

    @Test
    fun `DELETE device with committed blob drops quota and on-disk state`() = runTest2 {
        val creds = createDevice()
        val size = commitBlobAndReturnSize(creds, moduleA, "committed-payload".toByteArray())

        val accountId = UUID.fromString(creds.account)
        component.storageTracker().getUsage(accountId).usedBytes shouldBe size

        val devicePath = getDevicePath(creds)
        devicePath.exists() shouldBe true

        deleteDevice(creds)

        component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        devicePath.exists() shouldBe false

        // No session entries for that device remain.
        component.sessionRepo().hasActiveSessionsForModule(accountId, creds.deviceId, moduleA) shouldBe false
    }

    @Test
    fun `DELETE device with active session releases reservation`() = runTest2 {
        val creds = createDevice()
        val sessionId = startActiveSession(creds, moduleA, sizeBytes = 1024)

        val accountId = UUID.fromString(creds.account)
        component.storageTracker().getUsage(accountId).reservedBytes shouldBe 1024

        deleteDevice(creds)

        val usage = component.storageTracker().getUsage(accountId)
        usage.usedBytes shouldBe 0
        usage.reservedBytes shouldBe 0
        component.sessionRepo().getSession(sessionId, accountId, creds.deviceId, moduleA) shouldBe null
    }

    @Test
    fun `DELETE account cascades through route to remove blobs, sessions, and quota`() = runTest2 {
        val alice = createDevice()
        val bob = createDevice(alice)
        val accountId = UUID.fromString(alice.account)

        val sizeA = commitBlobAndReturnSize(alice, moduleA, "alice-blob".toByteArray())
        val sizeB = commitBlobAndReturnSize(bob, moduleB, "bob-blob-larger".toByteArray())
        startActiveSession(alice, moduleA, sizeBytes = 500)
        startActiveSession(bob, moduleB, sizeBytes = 700)

        component.storageTracker().getUsage(accountId).usedBytes shouldBe (sizeA + sizeB)
        component.storageTracker().getUsage(accountId).reservedBytes shouldBe (500 + 700)

        deleteAccount(alice).status shouldBe HttpStatusCode.OK

        val accountPath = getAccountPath(alice)
        accountPath.exists() shouldBe false

        val usage = component.storageTracker().getUsage(accountId)
        usage.usedBytes shouldBe 0
        usage.reservedBytes shouldBe 0
    }

    @Test
    fun `DELETE device with malformed module meta releases quota`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.teardown.malformed"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeRawModuleMeta(moduleDir, "CORRUPT")
            BlobFixtures.writeModulePayloadBlob(moduleDir, ByteArray(333))
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 333

            // Device wasn't registered via HTTP (seeded directly), so reach the service.
            val target = component.deviceRepo().getDevice(
                eu.darken.octi.server.device.DeviceKey(accountId, deviceId)
            ) ?: error("seeded device not loaded")
            kotlinx.coroutines.runBlocking { component.lifecycleService().deleteForDevice(accountId, target) }

            component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        }
    }

    @Test
    fun `DELETE device with missing module meta releases quota`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.teardown.missing"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModulePayloadBlob(moduleDir, ByteArray(222))
            // no module.json at all
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 222

            val target = component.deviceRepo().getDevice(
                eu.darken.octi.server.device.DeviceKey(accountId, deviceId)
            ) ?: error("seeded device not loaded")
            kotlinx.coroutines.runBlocking { component.lifecycleService().deleteForDevice(accountId, target) }

            component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        }
    }

    @Test
    fun `DELETE device with legacy v0 meta releases quota`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.teardown.legacyv0"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            // Legacy v0 meta — no schemaVersion field; recovery falls back to payload.blob.fileSize().
            BlobFixtures.writeRawModuleMeta(
                moduleDir,
                """{"id":"$moduleId","source":"$deviceId"}"""
            )
            BlobFixtures.writeModulePayloadBlob(moduleDir, ByteArray(111))
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 111

            val target = component.deviceRepo().getDevice(
                eu.darken.octi.server.device.DeviceKey(accountId, deviceId)
            ) ?: error("seeded device not loaded")
            kotlinx.coroutines.runBlocking { component.lifecycleService().deleteForDevice(accountId, target) }

            component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        }
    }

    @Test
    fun `recovery after device teardown reports zero usage`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.teardown.postrecovery"
        val ref = BlobFixtures.randomBlobRef(sizeBytes = 128)
        val dataPath = baseConfig.dataPath

        // Phase 1: seed committed blob, launch, confirm usage.
        runTest2(
            appConfig = baseConfig.copy(dataPath = dataPath),
            keepData = true,
            seed = { cfg ->
                BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
                val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
                BlobFixtures.writeModuleMeta(moduleDir, BlobFixtures.moduleMeta(moduleId, deviceId, blobRefs = listOf(ref)))
                BlobFixtures.writeBlobPayload(BlobFixtures.blobDir(moduleDir, ref.storageKey), ByteArray(128))
            }
        ) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 128

            val target = component.deviceRepo().getDevice(
                eu.darken.octi.server.device.DeviceKey(accountId, deviceId)
            ) ?: error("seeded device not loaded")
            kotlinx.coroutines.runBlocking { component.lifecycleService().deleteForDevice(accountId, target) }

            component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        }

        // Phase 2: relaunch on the same dataPath; recovery should rebuild to zero.
        runTest2(
            appConfig = baseConfig.copy(dataPath = dataPath, port = baseConfig.port + 1),
            keepData = false,
        ) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        }
    }
}
