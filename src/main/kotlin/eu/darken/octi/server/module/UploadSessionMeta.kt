package eu.darken.octi.server.module

import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.*

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UploadSessionMeta(
    @EncodeDefault val schemaVersion: Int = 1,
    val sessionId: String,
    val blobId: String,
    val storageKey: String,
    @Contextual val accountId: UUID,
    @Contextual val deviceId: UUID,
    val moduleId: String,
    val expectedSizeBytes: Long,
    val offsetBytes: Long,
    val hashAlgorithm: String? = null,
    val hashHex: String? = null,
    @Contextual val createdAt: Instant,
    @Contextual val lastActivityAt: Instant,
    @Contextual val expiresAt: Instant,
    val idleTtlSeconds: Long = 3600,
    val state: State,
) {
    @Serializable
    enum class State { ACTIVE, COMPLETE, ABORTED }

    fun isExpired(now: Instant = Instant.now()): Boolean {
        if (state == State.ABORTED) return true
        // Absolute expiry
        if (now.isAfter(expiresAt)) return true
        // Idle expiry
        if (now.isAfter(lastActivityAt.plusSeconds(idleTtlSeconds))) return true
        return false
    }

    fun matchesScope(accountId: UUID, moduleId: String): Boolean {
        return this.accountId == accountId && this.moduleId == moduleId
    }

    fun matchesScopeWithDevice(accountId: UUID, deviceId: UUID, moduleId: String): Boolean {
        return this.accountId == accountId && this.deviceId == deviceId && this.moduleId == moduleId
    }
}
