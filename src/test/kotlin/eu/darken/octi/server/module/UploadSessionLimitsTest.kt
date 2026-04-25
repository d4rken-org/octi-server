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
    fun `COMPLETE sessions count toward the per-device cap`() {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val moduleId = "eu.darken.octi.limits.complete"

        runTest2(seed = { cfg -> BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceId) }) {
            val cap = config.maxActiveUploadSessionsPerDevice

            // Create cap zero-byte sessions and finalize them all to COMPLETE state.
            // Pre-fix only ACTIVE counted, so this would have allowed unlimited create-finalize cycles.
            repeat(cap) {
                val session = component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
                runBlocking {
                    component.sessionRepo().finalizeSession(
                        sessionId = session.sessionId,
                        accountId = accountId,
                        moduleId = moduleId,
                        hashAlgorithm = "sha256",
                        // SHA-256 of empty input.
                        hashHex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    )
                }
            }

            // All sessions are now COMPLETE — must still count toward cap.
            shouldThrow<SessionLimitExceededException> {
                component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceId, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
            }
        }
    }

    @Test
    fun `per-account cap fires when sessions are spread across devices`() {
        val accountId = UUID.randomUUID()
        val deviceA = UUID.randomUUID()
        val deviceB = UUID.randomUUID()
        val moduleId = "eu.darken.octi.limits.peraccount"

        runTest2(
            appConfig = baseConfig.copy(
                maxActiveUploadSessionsPerAccount = 2,
                maxActiveUploadSessionsPerDevice = 8, // higher than account cap, so account cap bites first
            ),
            seed = { cfg ->
                BlobFixtures.seedAccountDevice(cfg.dataPath, accountId, deviceA)
                BlobFixtures.writeDevice(cfg.dataPath, accountId, deviceB)
            },
        ) {
            // 1 session on each device — account total = 2, hits account cap.
            component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceA, moduleId = moduleId,
                expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
            )
            component.sessionRepo().createSession(
                accountId = accountId, deviceId = deviceB, moduleId = moduleId,
                expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
            )

            // Third session on either device — account cap rejects.
            val ex = shouldThrow<SessionLimitExceededException> {
                component.sessionRepo().createSession(
                    accountId = accountId, deviceId = deviceA, moduleId = moduleId,
                    expectedSizeBytes = 0, hashAlgorithm = null, hashHex = null,
                )
            }
            ex.limit shouldBe 2
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
