package eu.darken.octi.server.module

import eu.darken.octi.TestRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Regression test for the per-device active-upload-session cap.
 *
 * The route accepts zero-byte sessions without consuming storage quota
 * ([BlobRoute.createSession] gates the `tryReserve` call on `sizeBytes > 0`),
 * which would otherwise let a misbehaving client open as many sessions as it
 * wanted. The active-session count cap in
 * [UploadSessionRepo.createSession] is the safety net for that case — these
 * tests pin it down so a future refactor doesn't drop the protection silently.
 */
class UploadSessionLimitsTest : TestRunner() {

    @Test
    fun `per-device cap rejects further zero-byte sessions`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.limits.zero"

        runTest2(seed = { cfg -> BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId) }) {
            val cap = config.maxActiveUploadSessionsPerDevice

            // Up to the cap: every zero-byte create succeeds without touching the storage tracker.
            repeat(cap) {
                component.sessionRepo().createSession(
                    accountId = accountId,
                    deviceId = deviceId,
                    moduleId = moduleId,
                    expectedSizeBytes = 0,
                    hashAlgorithm = null,
                    hashHex = null,
                )
            }
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0

            // Cap + 1: 409 territory.
            val exception = shouldThrow<SessionLimitExceededException> {
                component.sessionRepo().createSession(
                    accountId = accountId,
                    deviceId = deviceId,
                    moduleId = moduleId,
                    expectedSizeBytes = 0,
                    hashAlgorithm = null,
                    hashHex = null,
                )
            }
            exception.limit shouldBe cap
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
        }
    }

    @Test
    fun `aborting a zero-byte session frees a slot`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.limits.recover"

        runTest2(seed = { cfg -> BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId) }) {
            val cap = config.maxActiveUploadSessionsPerDevice

            val firstSession = component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
            )
            repeat(cap - 1) {
                component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
            }

            // Saturated → next create rejects.
            shouldThrow<SessionLimitExceededException> {
                component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
            }

            // Abort the first session — slot is reusable on the next attempt.
            runBlocking {
                component.sessionRepo().abortSession(firstSession.sessionId, accountId, moduleId)
            }
            component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
            )
            // Tracker still untouched throughout (zero-byte never reserved).
            component.storageTracker().getUsage(accountId).reservedBytes shouldBe 0
        }
    }

    @Test
    fun `cap is per-device, not per-account`() {
        val accountId = UUID.randomUUID()
        val deviceA = UUID.randomUUID()
        val deviceB = UUID.randomUUID()
        val moduleId = "eu.darken.octi.limits.perdevice"

        runTest2(
            seed = { cfg ->
                BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceA)
                BlobFixtures.writeDevice(cfg.dataPath, accountId, deviceB)
            },
        ) {
            val cap = config.maxActiveUploadSessionsPerDevice

            // Saturate device A.
            repeat(cap) {
                component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceA, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
            }
            shouldThrow<SessionLimitExceededException> {
                component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceA, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
            }

            // Device B is independent — cap doesn't transfer across devices.
            component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceB, moduleId = moduleId,
                expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
            )
        }
    }
}
