package eu.darken.octi.server.module

import eu.darken.octi.TestRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * Tests [UploadSessionRepo.reapExpiredSessions] — the background GC that releases
 * reservations for sessions whose TTL elapsed.
 *
 * Test pattern: create a real session via `createSession()` (so the quota reservation
 * is real), then swap the in-memory entry with an expired meta via `loadSession()`.
 * Reap then finds the expired entry and releases. Pre-seeding expired state on disk
 * doesn't work because `StartupRecoveryService` would reap it on boot, before reap
 * ever runs in the test.
 */
class UploadSessionGcTest : TestRunner() {

    private fun seedAccountDeviceFor(
        accountId: UUID,
        deviceId: UUID,
    ): (java.nio.file.Path) -> Unit = { dataPath ->
        BlobFixtures.seedAccountDevice(dataPath, accountId, deviceId)
    }

    @Test
    fun `active session is not reaped`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()

        runTest2(seed = { cfg -> seedAccountDeviceFor(accountId, deviceId)(cfg.dataPath) }) {
            component.storageTracker().tryReserve(accountId, 500) shouldBe true
            val session = component.sessionRepo().createSession(
                accountId = accountId,
                deviceId = deviceId,
                moduleId = "eu.darken.octi.gc.active",
                expectedSizeBytes = 500,
                hashAlgorithm = null,
                hashHex = null,
            )

            runBlocking { component.sessionRepo().reapExpiredSessions() }

            component.sessionRepo().getSession(session.sessionId, accountId, session.moduleId) shouldBe
                session.copy()
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 500
        }
    }

    @Test
    fun `idle-expired session is reaped and reservation released`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.gc.idle"

        runTest2(seed = { cfg -> seedAccountDeviceFor(accountId, deviceId)(cfg.dataPath) }) {
            component.storageTracker().tryReserve(accountId, 500) shouldBe true
            val session = component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 500, hashAlgorithm = null, hashHex = null,
            )

            // Swap the in-memory entry so the copy in the map reflects an idle-expired TTL.
            val sessionDirPath = BlobFixtures.sessionDir(
                BlobFixtures.moduleDir(config.dataPath, accountId, deviceId, moduleId),
                session.sessionId,
            )
            val expired = session.copy(
                lastActivityAt = Instant.now().minusSeconds(7200),
                idleTtlSeconds = 3600,
            )
            component.sessionRepo().loadSession(expired, sessionDirPath)

            runBlocking { component.sessionRepo().reapExpiredSessions() }

            component.sessionRepo().getSession(session.sessionId, accountId, moduleId) shouldBe null
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDirPath.toFile().exists() shouldBe false
        }
    }

    @Test
    fun `absolute-expired session is reaped and reservation released`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.gc.abs"

        runTest2(seed = { cfg -> seedAccountDeviceFor(accountId, deviceId)(cfg.dataPath) }) {
            component.storageTracker().tryReserve(accountId, 750) shouldBe true
            val session = component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 750, hashAlgorithm = null, hashHex = null,
            )

            val sessionDirPath = BlobFixtures.sessionDir(
                BlobFixtures.moduleDir(config.dataPath, accountId, deviceId, moduleId),
                session.sessionId,
            )
            val expired = session.copy(
                lastActivityAt = Instant.now(), // still fresh
                expiresAt = Instant.now().minusSeconds(1), // but absolute TTL is past
            )
            component.sessionRepo().loadSession(expired, sessionDirPath)

            runBlocking { component.sessionRepo().reapExpiredSessions() }

            component.sessionRepo().getSession(session.sessionId, accountId, moduleId) shouldBe null
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDirPath.toFile().exists() shouldBe false
        }
    }

    @Test
    fun `COMPLETE expired session also releases reservation on reap`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.gc.completeexpired"

        runTest2(seed = { cfg -> seedAccountDeviceFor(accountId, deviceId)(cfg.dataPath) }) {
            component.storageTracker().tryReserve(accountId, 300) shouldBe true
            val session = component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 300, hashAlgorithm = null, hashHex = null,
            )

            val sessionDirPath = BlobFixtures.sessionDir(
                BlobFixtures.moduleDir(config.dataPath, accountId, deviceId, moduleId),
                session.sessionId,
            )
            val expiredComplete = session.copy(
                state = UploadSessionMeta.State.COMPLETE,
                expiresAt = Instant.now().minusSeconds(10),
            )
            component.sessionRepo().loadSession(expiredComplete, sessionDirPath)

            runBlocking { component.sessionRepo().reapExpiredSessions() }

            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDirPath.toFile().exists() shouldBe false
        }
    }

    @Test
    fun `finalize shortens idle TTL so a quiet COMPLETE session reaps in minutes, not hours`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.gc.completeshortidle"

        runTest2(seed = { cfg -> seedAccountDeviceFor(accountId, deviceId)(cfg.dataPath) }) {
            component.storageTracker().tryReserve(accountId, 100) shouldBe true
            val session = component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 100, hashAlgorithm = null, hashHex = null,
            )
            val sessionDirPath = BlobFixtures.sessionDir(
                BlobFixtures.moduleDir(config.dataPath, accountId, deviceId, moduleId),
                session.sessionId,
            )

            // Pre-stage payload.part on disk and swap the in-memory meta to offsetBytes=100 so
            // finalizeSession's offset check passes without going through PATCH (which requires
            // a real ByteReadChannel and is overkill for a TTL test).
            val payload = ByteArray(100)
            sessionDirPath.resolve(UploadSessionRepo.PART_FILENAME).toFile().writeBytes(payload)
            val sha = run {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update(payload)
                md.digest().joinToString("") { "%02x".format(it) }
            }
            component.sessionRepo().loadSession(session.copy(offsetBytes = 100), sessionDirPath)

            val finalizeResult = runBlocking {
                component.sessionRepo().finalizeSession(
                    sessionId = session.sessionId,
                    accountId = accountId,
                    moduleId = moduleId,
                    hashAlgorithm = "SHA-256",
                    hashHex = sha,
                )
            }
            finalizeResult.shouldBeInstanceOf<UploadSessionRepo.FinalizeResult.Success>()

            val completeMeta = component.sessionRepo().getSession(session.sessionId, accountId, moduleId)!!
            completeMeta.idleTtlSeconds shouldBe UploadSessionRepo.COMPLETE_IDLE_TTL_SECONDS

            // Fresh COMPLETE: not reaped under the shorter idle TTL.
            runBlocking { component.sessionRepo().reapExpiredSessions() }
            component.sessionRepo().getSession(session.sessionId, accountId, moduleId) shouldNotBe null

            // Quiet for longer than the shortened idle TTL, but well under the ACTIVE-state TTL.
            val agedComplete = completeMeta.copy(
                lastActivityAt = Instant.now().minusSeconds(UploadSessionRepo.COMPLETE_IDLE_TTL_SECONDS + 60),
            )
            component.sessionRepo().loadSession(agedComplete, sessionDirPath)

            runBlocking { component.sessionRepo().reapExpiredSessions() }

            component.sessionRepo().getSession(session.sessionId, accountId, moduleId) shouldBe null
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
            sessionDirPath.toFile().exists() shouldBe false
        }
    }

    // Zero-byte sessions skip the release branch (guarded by expectedSizeBytes > 0).
    @Test
    fun `zero-byte expired session reaped without tracker delta`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.gc.zero"

        runTest2(seed = { cfg -> seedAccountDeviceFor(accountId, deviceId)(cfg.dataPath) }) {
            val session = component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
            )
            val sessionDirPath = BlobFixtures.sessionDir(
                BlobFixtures.moduleDir(config.dataPath, accountId, deviceId, moduleId),
                session.sessionId,
            )
            val expired = session.copy(expiresAt = Instant.now().minusSeconds(60))
            component.sessionRepo().loadSession(expired, sessionDirPath)

            // Non-zero reservation from an unrelated operation so we can observe it's untouched.
            component.storageTracker().adjustUsed(accountId, 10)
            val before = component.storageTracker().getUsage(accountId)

            runBlocking { component.sessionRepo().reapExpiredSessions() }

            val after = component.storageTracker().getUsage(accountId)
            after shouldBe before
            sessionDirPath.toFile().exists() shouldBe false
        }
    }
}
