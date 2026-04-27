package eu.darken.octi.server.module

import eu.darken.octi.TestRunner
import eu.darken.octi.sha256Hex
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*
import kotlin.io.path.exists

class StartupRecoveryServiceTest : TestRunner() {

    // ---------- module / blob branches ----------

    @Test
    fun `happy-path reload rebuilds used bytes from committed module`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.happy"
        val ref = BlobFixtures.randomBlobRef(sizeBytes = 512)

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModuleMeta(
                moduleDir,
                BlobFixtures.moduleMeta(moduleId, deviceId, blobRefs = listOf(ref)),
            )
            BlobFixtures.writeBlobPayload(BlobFixtures.blobDir(moduleDir, ref.storageKey), ByteArray(512))
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 512
        }
    }

    @Test
    fun `orphan blob directories are reclaimed, referenced blob is kept`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.orphan"
        val liveRef = BlobFixtures.randomBlobRef(sizeBytes = 256)
        val orphanStorageKey = UUID.randomUUID().toString()

        lateinit var liveBlobDir: java.nio.file.Path
        lateinit var orphanBlobDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModuleMeta(
                moduleDir,
                BlobFixtures.moduleMeta(moduleId, deviceId, blobRefs = listOf(liveRef)),
            )
            liveBlobDir = BlobFixtures.blobDir(moduleDir, liveRef.storageKey).also {
                BlobFixtures.writeBlobPayload(it, ByteArray(256))
            }
            orphanBlobDir = BlobFixtures.blobDir(moduleDir, orphanStorageKey).also {
                BlobFixtures.writeBlobPayload(it, ByteArray(999))
            }
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 256
            liveBlobDir.exists() shouldBe true
            orphanBlobDir.exists() shouldBe false
        }
    }

    @Test
    fun `malformed module json falls back to counting payload blob size`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.malformed"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeRawModuleMeta(moduleDir, "NOT_JSON_AT_ALL")
            BlobFixtures.writeModulePayloadBlob(moduleDir, ByteArray(384))
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 384
        }
    }

    @Test
    fun `missing module json with payload blob counts legacy unmigrated module`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.legacy"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModulePayloadBlob(moduleDir, ByteArray(128))
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 128
        }
    }

    @Test
    fun `module reference with missing blob payload is not counted`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.missingpayload"
        val ref = BlobFixtures.randomBlobRef(sizeBytes = 200)

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModuleMeta(
                moduleDir,
                BlobFixtures.moduleMeta(moduleId, deviceId, blobRefs = listOf(ref)),
            )
            // deliberately NOT writing payload.blob
        }) {
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 0
        }
    }

    @Test
    fun `two accounts are rebuilt independently`() {
        val accountA = UUID.randomUUID()
        val accountB = UUID.randomUUID()
        val deviceA = UUID.randomUUID()
        val deviceB = UUID.randomUUID()
        val modA = "eu.darken.octi.recovery.a"
        val modB = "eu.darken.octi.recovery.b"
        val refA = BlobFixtures.randomBlobRef(100)
        val refB = BlobFixtures.randomBlobRef(250)

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountA, deviceA)
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountB, deviceB)

            val modDirA = BlobFixtures.moduleDir(cfg.dataPath, accountA, deviceA, modA)
            BlobFixtures.writeModuleMeta(modDirA, BlobFixtures.moduleMeta(modA, deviceA, blobRefs = listOf(refA)))
            BlobFixtures.writeBlobPayload(BlobFixtures.blobDir(modDirA, refA.storageKey), ByteArray(100))

            val modDirB = BlobFixtures.moduleDir(cfg.dataPath, accountB, deviceB, modB)
            BlobFixtures.writeModuleMeta(modDirB, BlobFixtures.moduleMeta(modB, deviceB, blobRefs = listOf(refB)))
            BlobFixtures.writeBlobPayload(BlobFixtures.blobDir(modDirB, refB.storageKey), ByteArray(250))
        }) {
            component.storageTracker().getUsage(accountA).usedBytes shouldBe 100
            component.storageTracker().getUsage(accountB).usedBytes shouldBe 250
        }
    }

    // ---------- session branches ----------

    @Test
    fun `malformed session json deletes session dir and counts no reservation`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.malformed"

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            sessionDir = BlobFixtures.sessionDir(moduleDir, UUID.randomUUID().toString()).also {
                BlobFixtures.writeRawSessionMeta(it, "NOT_JSON")
            }
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDir.exists() shouldBe false
        }
    }

    @Test
    fun `expired session is deleted and not reserved`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.expired"

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val expired = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 1024,
                expiresAt = Instant.now().minusSeconds(3600),
            )
            sessionDir = BlobFixtures.sessionDir(moduleDir, expired.sessionId).also {
                BlobFixtures.writeSessionMeta(it, expired)
            }
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDir.exists() shouldBe false
        }
    }

    @Test
    fun `session whose metadata module scope does not match path is deleted`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val pathModuleId = "eu.darken.octi.recovery.session.pathscope"
        val metaModuleId = "eu.darken.octi.recovery.session.other"

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, pathModuleId)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId,
                deviceId = deviceId,
                moduleId = metaModuleId,
                expectedSizeBytes = 100,
                state = UploadSessionMeta.State.ACTIVE,
            )
            sessionDir = BlobFixtures.sessionDir(moduleDir, session.sessionId).also {
                BlobFixtures.writeSessionMeta(it, session)
                BlobFixtures.writeSessionPart(it, ByteArray(0))
            }
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDir.exists() shouldBe false
        }
    }

    @Test
    fun `COMPLETE session with mismatched SHA-256 is deleted`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.badhash"

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId,
                deviceId = deviceId,
                moduleId = moduleId,
                expectedSizeBytes = 4,
                offsetBytes = 4,
                state = UploadSessionMeta.State.COMPLETE,
                hashAlgorithm = "sha256",
                hashHex = "0".repeat(64),
            )
            sessionDir = BlobFixtures.sessionDir(moduleDir, session.sessionId).also {
                BlobFixtures.writeSessionMeta(it, session)
                BlobFixtures.writeSessionBlob(it, "data".toByteArray())
            }
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDir.exists() shouldBe false
        }
    }

    @Test
    fun `COMPLETE session whose blobId is already live is deleted`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.stalecommitted"
        val liveRef = BlobFixtures.randomBlobRef(sizeBytes = 64)

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModuleMeta(
                moduleDir,
                BlobFixtures.moduleMeta(moduleId, deviceId, blobRefs = listOf(liveRef)),
            )
            BlobFixtures.writeBlobPayload(BlobFixtures.blobDir(moduleDir, liveRef.storageKey), ByteArray(64))

            val staleSession = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 64,
                state = UploadSessionMeta.State.COMPLETE,
                blobId = liveRef.blobId,
            )
            sessionDir = BlobFixtures.sessionDir(moduleDir, staleSession.sessionId).also {
                BlobFixtures.writeSessionMeta(it, staleSession)
                BlobFixtures.writeSessionBlob(it, ByteArray(64))
            }
        }) {
            val usage = component.storageTracker().getUsage(accountId)
            usage.usedBytes shouldBe 64
            usage.reservedBytes shouldBe 0
            sessionDir.exists() shouldBe false
        }
    }

    @Test
    fun `COMPLETE session with missing payload blob is deleted`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.completemissingblob"

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 100,
                state = UploadSessionMeta.State.COMPLETE,
            )
            sessionDir = BlobFixtures.sessionDir(moduleDir, session.sessionId).also {
                BlobFixtures.writeSessionMeta(it, session)
                // no payload.blob written
            }
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDir.exists() shouldBe false
        }
    }

    @Test
    fun `ACTIVE session with only payload blob is promoted to COMPLETE and reserved`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.promoted"

        lateinit var sessionDir: java.nio.file.Path

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val payload = ByteArray(500)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 500,
                offsetBytes = 0,
                state = UploadSessionMeta.State.ACTIVE,
                hashAlgorithm = "sha256",
                hashHex = payload.sha256Hex(),
            )
            sessionDir = BlobFixtures.sessionDir(moduleDir, session.sessionId).also {
                BlobFixtures.writeSessionMeta(it, session)
                BlobFixtures.writeSessionBlob(it, payload)
                // no payload.part
            }
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 500
            sessionDir.exists() shouldBe true
            val reloadedRaw = sessionDir.resolve("session.json").toFile().readText()
            (reloadedRaw.contains("\"state\": \"COMPLETE\"") || reloadedRaw.contains("\"state\":\"COMPLETE\"")) shouldBe true
        }
    }

    @Test
    fun `ACTIVE session with part larger than offset advances offset`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.partbig"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 1000,
                offsetBytes = 100,
                state = UploadSessionMeta.State.ACTIVE,
            )
            val sDir = BlobFixtures.sessionDir(moduleDir, session.sessionId)
            BlobFixtures.writeSessionMeta(sDir, session)
            BlobFixtures.writeSessionPart(sDir, ByteArray(700))
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 1000
        }
    }

    @Test
    fun `ACTIVE session with part smaller than offset truncates offset`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.partsmall"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 1000,
                offsetBytes = 500,
                state = UploadSessionMeta.State.ACTIVE,
            )
            val sDir = BlobFixtures.sessionDir(moduleDir, session.sessionId)
            BlobFixtures.writeSessionMeta(sDir, session)
            BlobFixtures.writeSessionPart(sDir, ByteArray(200))
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 1000
        }
    }

    @Test
    fun `ACTIVE promoted from payload blob with size mismatch is deleted`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.session.promotedmismatch"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            val session = BlobFixtures.sessionMeta(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 500,
                offsetBytes = 0,
                state = UploadSessionMeta.State.ACTIVE,
            )
            val sDir = BlobFixtures.sessionDir(moduleDir, session.sessionId)
            BlobFixtures.writeSessionMeta(sDir, session)
            BlobFixtures.writeSessionBlob(sDir, ByteArray(123))  // not 500
        }) {
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
        }
    }

    @Test
    fun `document_size_normalized rebuilds usedBytes from actual payload size`() {
        // Crash mid-document-write before module.json rename: meta says 100 but the
        // payload.blob on disk is only 50 bytes. Plan §"Startup Recovery" item 4.
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.recovery.docsize"

        runTest2(seed = { cfg ->
            BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId)
            val moduleDir = BlobFixtures.moduleDir(cfg.dataPath, accountId, deviceId, moduleId)
            BlobFixtures.writeModuleMeta(
                moduleDir,
                BlobFixtures.moduleMeta(moduleId, deviceId, documentSizeBytes = 100),
            )
            BlobFixtures.writeModulePayloadBlob(moduleDir, ByteArray(50))
        }) {
            // Recovery should normalize documentSizeBytes to 50, not 100.
            component.storageTracker().getUsage(accountId).usedBytes shouldBe 50
        }
    }
}
