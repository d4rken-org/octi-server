package eu.darken.octi.server.module

import eu.darken.octi.server.account.Account
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.common.serialization.InstantSerializer
import eu.darken.octi.server.common.serialization.UUIDSerializer
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Shared on-disk fixtures for recovery, GC, and teardown tests. All tests that pre-seed
 * state before `App.launch()` go through these helpers so the layout stays in one place.
 */
internal object BlobFixtures {

    val testJson: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        serializersModule = SerializersModule {
            contextual(Instant::class, InstantSerializer)
            contextual(UUID::class, UUIDSerializer)
        }
    }

    fun accountDir(dataPath: Path, accountId: AccountId): Path =
        dataPath.resolve("accounts").resolve(accountId.toString())

    fun deviceDir(dataPath: Path, accountId: AccountId, deviceId: DeviceId): Path =
        accountDir(dataPath, accountId).resolve("devices").resolve(deviceId.toString())

    fun moduleDir(dataPath: Path, accountId: AccountId, deviceId: DeviceId, moduleId: ModuleId): Path =
        deviceDir(dataPath, accountId, deviceId).resolve("modules").resolve(moduleId.toModuleDirName())

    fun blobDir(moduleDir: Path, storageKey: String): Path =
        moduleDir.resolve("blobs").resolve(storageKey.take(4)).resolve(storageKey)

    fun sessionDir(moduleDir: Path, sessionId: String): Path =
        moduleDir.resolve("sessions").resolve(sessionId)

    fun writeAccount(dataPath: Path, accountId: AccountId, createdAt: Instant = Instant.now()): Path {
        val dir = accountDir(dataPath, accountId).also { it.createDirectories() }
        val data = Account.Data(id = accountId, createdAt = createdAt)
        dir.resolve("account.json").writeText(testJson.encodeToString(Account.Data.serializer(), data))
        return dir
    }

    fun writeDevice(
        dataPath: Path,
        accountId: AccountId,
        deviceId: DeviceId,
        password: String = "test-password-0123456789-0123456789-0123456789-0123",
    ): Path {
        val dir = deviceDir(dataPath, accountId, deviceId).also { it.createDirectories() }
        val data = Device.Data(id = deviceId, password = password)
        dir.resolve("device.json").writeText(testJson.encodeToString(Device.Data.serializer(), data))
        return dir
    }

    /**
     * Seeds the minimum account+device fixtures so AccountRepo/DeviceRepo init doesn't
     * wipe the account directory before StartupRecoveryService runs.
     */
    fun seedAccountDevice(dataPath: Path, accountId: AccountId, deviceId: DeviceId) {
        writeAccount(dataPath, accountId)
        writeDevice(dataPath, accountId, deviceId)
    }

    fun writeModuleMeta(moduleDir: Path, meta: ModuleMeta) {
        moduleDir.createDirectories()
        moduleDir.resolve("module.json").writeText(testJson.encodeToString(ModuleMeta.serializer(), meta))
    }

    fun writeRawModuleMeta(moduleDir: Path, raw: String) {
        moduleDir.createDirectories()
        moduleDir.resolve("module.json").writeText(raw)
    }

    fun writeSessionMeta(sessionDir: Path, meta: UploadSessionMeta) {
        sessionDir.createDirectories()
        sessionDir.resolve("session.json").writeText(testJson.encodeToString(UploadSessionMeta.serializer(), meta))
    }

    fun writeRawSessionMeta(sessionDir: Path, raw: String) {
        sessionDir.createDirectories()
        sessionDir.resolve("session.json").writeText(raw)
    }

    fun writeBlobPayload(storageKeyDir: Path, bytes: ByteArray) {
        storageKeyDir.createDirectories()
        storageKeyDir.resolve("payload.blob").writeBytes(bytes)
    }

    fun writeSessionPart(sessionDir: Path, bytes: ByteArray) {
        sessionDir.createDirectories()
        sessionDir.resolve("payload.part").writeBytes(bytes)
    }

    fun writeSessionBlob(sessionDir: Path, bytes: ByteArray) {
        sessionDir.createDirectories()
        sessionDir.resolve("payload.blob").writeBytes(bytes)
    }

    fun writeModulePayloadBlob(moduleDir: Path, bytes: ByteArray) {
        moduleDir.createDirectories()
        moduleDir.resolve("payload.blob").writeBytes(bytes)
    }

    fun randomBlobRef(sizeBytes: Long): BlobRef = BlobRef(
        blobId = UUID.randomUUID().toString(),
        storageKey = UUID.randomUUID().toString(),
        sizeBytes = sizeBytes,
    )

    fun moduleMeta(
        moduleId: ModuleId,
        sourceDeviceId: DeviceId,
        documentSizeBytes: Long = 0,
        blobRefs: List<BlobRef> = emptyList(),
        etag: String = UUID.randomUUID().toString(),
        modifiedAt: Instant = Instant.now(),
    ): ModuleMeta = ModuleMeta(
        schemaVersion = 1,
        moduleId = moduleId,
        sourceDeviceId = sourceDeviceId,
        etag = etag,
        modifiedAt = modifiedAt,
        documentSizeBytes = documentSizeBytes,
        blobRefs = blobRefs,
    )

    fun sessionMeta(
        accountId: AccountId,
        deviceId: DeviceId,
        moduleId: ModuleId,
        expectedSizeBytes: Long,
        offsetBytes: Long = 0,
        state: UploadSessionMeta.State = UploadSessionMeta.State.ACTIVE,
        sessionId: String = UUID.randomUUID().toString(),
        blobId: String = UUID.randomUUID().toString(),
        storageKey: String = UUID.randomUUID().toString(),
        createdAt: Instant = Instant.now(),
        lastActivityAt: Instant = createdAt,
        expiresAt: Instant = createdAt.plusSeconds(86400),
        idleTtlSeconds: Long = 3600,
        hashAlgorithm: String? = null,
        hashHex: String? = null,
    ): UploadSessionMeta = UploadSessionMeta(
        sessionId = sessionId,
        blobId = blobId,
        storageKey = storageKey,
        accountId = accountId,
        deviceId = deviceId,
        moduleId = moduleId,
        expectedSizeBytes = expectedSizeBytes,
        offsetBytes = offsetBytes,
        hashAlgorithm = hashAlgorithm,
        hashHex = hashHex,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        expiresAt = expiresAt,
        idleTtlSeconds = idleTtlSeconds,
        state = state,
    )
}
