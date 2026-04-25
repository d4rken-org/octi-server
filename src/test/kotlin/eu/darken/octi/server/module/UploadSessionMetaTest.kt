package eu.darken.octi.server.module

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UploadSessionMetaTest {

    private fun fakeMeta(
        state: UploadSessionMeta.State,
        createdAt: Instant = Instant.now(),
        lastActivityAt: Instant = createdAt,
        expiresAt: Instant = createdAt.plusSeconds(86_400),
        idleTtlSeconds: Long = 3600,
        completeIdleTtlSeconds: Long = 600,
    ) = UploadSessionMeta(
        sessionId = "s",
        blobId = "b",
        storageKey = "k",
        accountId = UUID.randomUUID(),
        deviceId = UUID.randomUUID(),
        moduleId = "m",
        expectedSizeBytes = 0,
        offsetBytes = 0,
        hashAlgorithm = null,
        hashHex = null,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        expiresAt = expiresAt,
        idleTtlSeconds = idleTtlSeconds,
        completeIdleTtlSeconds = completeIdleTtlSeconds,
        state = state,
    )

    @Test
    fun `ACTIVE uses idleTtlSeconds for idle expiry`() {
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val meta = fakeMeta(
            state = UploadSessionMeta.State.ACTIVE,
            createdAt = now,
            lastActivityAt = now,
            idleTtlSeconds = 100,
            completeIdleTtlSeconds = 10,
        )

        meta.isExpired(now.plusSeconds(50)) shouldBe false
        meta.isExpired(now.plusSeconds(101)) shouldBe true
    }

    @Test
    fun `COMPLETE uses completeIdleTtlSeconds for idle expiry`() {
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val meta = fakeMeta(
            state = UploadSessionMeta.State.COMPLETE,
            createdAt = now,
            lastActivityAt = now,
            idleTtlSeconds = 3600,
            completeIdleTtlSeconds = 10,
        )

        meta.isExpired(now.plusSeconds(5)) shouldBe false
        meta.isExpired(now.plusSeconds(11)) shouldBe true
    }

    @Test
    fun `absolute expiry overrides idle for both states`() {
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val meta = fakeMeta(
            state = UploadSessionMeta.State.ACTIVE,
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now.plusSeconds(50),
            idleTtlSeconds = 3600,
            completeIdleTtlSeconds = 10,
        )

        meta.isExpired(now.plusSeconds(51)) shouldBe true
    }

    @Test
    fun `ABORTED is always expired`() {
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val meta = fakeMeta(
            state = UploadSessionMeta.State.ABORTED,
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now.plusSeconds(99999),
        )

        meta.isExpired(now) shouldBe true
    }
}
