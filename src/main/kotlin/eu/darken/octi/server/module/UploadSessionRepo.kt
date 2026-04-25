package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.DeviceId
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@Singleton
class UploadSessionRepo @Inject constructor(
    private val appScope: AppScope,
    private val config: App.Config,
    private val serializer: Json,
    private val storageTracker: AccountStorageTracker,
) {

    private val sessions = ConcurrentHashMap<String, SessionState>()

    data class SessionState(
        val meta: UploadSessionMeta,
        val sessionDir: Path,
        val lock: Mutex = Mutex(),
    )

    sealed interface AppendResult {
        data class Success(val newOffset: Long) : AppendResult
        data class OffsetMismatch(val expectedOffset: Long) : AppendResult
        data object SizeExceeded : AppendResult
        data object ChunkTooLarge : AppendResult
        data object SessionNotFound : AppendResult
        data class SessionNotActive(val state: String) : AppendResult
    }

    sealed interface FinalizeResult {
        data class Success(val blobId: String, val sizeBytes: Long) : FinalizeResult
        data class AlreadyComplete(val blobId: String, val sizeBytes: Long) : FinalizeResult
        data object SessionNotFound : FinalizeResult
        data class NotFullyUploaded(val currentOffset: Long, val expectedSize: Long) : FinalizeResult
        data object ChecksumMismatch : FinalizeResult
        data object ChecksumConflict : FinalizeResult
        data object MissingChecksum : FinalizeResult
    }

    // region Lifecycle

    /**
     * Starts the background GC coroutine. Must be called after startup recovery completes.
     */
    fun startGC() {
        appScope.launch(Dispatchers.IO) {
            delay(config.moduleGCInterval.toMillis())
            while (currentCoroutineContext().isActive) {
                try {
                    reapExpiredSessions()
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Session GC failed: ${e.message}" }
                }
                delay(config.moduleGCInterval.toMillis())
            }
        }
    }

    internal suspend fun reapExpiredSessions() {
        val now = Instant.now()
        val expired = sessions.values.filter { it.meta.isExpired(now) }
        if (expired.isNotEmpty()) {
            log(TAG) { "GC: reaping ${expired.size} expired sessions" }
        }
        for (state in expired) {
            terminateSession(state.meta.sessionId, "gc_expired")
        }
    }

    /**
     * Loads a session from disk during startup recovery. No scope validation needed.
     */
    fun loadSession(meta: UploadSessionMeta, sessionDir: Path) {
        sessions[meta.sessionId] = SessionState(meta = meta, sessionDir = sessionDir)
        log(TAG, VERBOSE) { "loadSession: loaded ${meta.sessionId}, state=${meta.state}" }
    }

    // endregion

    // region Session CRUD

    fun createSession(
        accountId: AccountId,
        deviceId: DeviceId,
        moduleId: ModuleId,
        expectedSizeBytes: Long,
        hashAlgorithm: String?,
        hashHex: String?,
    ): UploadSessionMeta {
        // Enforce session limit per device
        val activeCount = sessions.values.count {
            it.meta.accountId == accountId && it.meta.deviceId == deviceId
                && it.meta.state == UploadSessionMeta.State.ACTIVE
                && !it.meta.isExpired()
        }
        if (activeCount >= config.maxActiveUploadSessionsPerDevice) {
            throw SessionLimitExceededException(config.maxActiveUploadSessionsPerDevice)
        }

        val sessionId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        val storageKey = UUID.randomUUID().toString()
        val now = Instant.now()

        val moduleHash = moduleId.toModuleDirName()
        val sessionDir = config.dataPath
            .resolve("accounts").resolve(accountId.toString())
            .resolve("devices").resolve(deviceId.toString())
            .resolve("modules").resolve(moduleHash)
            .resolve("sessions").resolve(sessionId)

        sessionDir.createDirectories()

        val meta = UploadSessionMeta(
            sessionId = sessionId,
            blobId = blobId,
            storageKey = storageKey,
            accountId = accountId,
            deviceId = deviceId,
            moduleId = moduleId,
            expectedSizeBytes = expectedSizeBytes,
            offsetBytes = 0,
            hashAlgorithm = hashAlgorithm,
            hashHex = hashHex,
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now.plusSeconds(config.absoluteSessionTtlSeconds),
            idleTtlSeconds = config.idleSessionTtlSeconds,
            state = UploadSessionMeta.State.ACTIVE,
        )

        persistSessionMeta(sessionDir, meta)
        sessionDir.resolve(PART_FILENAME).createFile()
        sessions[sessionId] = SessionState(meta = meta, sessionDir = sessionDir)

        log(TAG) { "createSession: session=$sessionId, blob=$blobId, size=$expectedSizeBytes" }
        return meta
    }

    /**
     * Gets session metadata with scope validation and synchronous expiry check.
     */
    fun getSession(sessionId: String, accountId: AccountId, moduleId: ModuleId): UploadSessionMeta? {
        val state = sessions[sessionId] ?: return null
        if (!state.meta.matchesScope(accountId, moduleId)) return null
        if (state.meta.isExpired()) {
            // Fire-and-forget termination (don't block the caller)
            appScope.launch { terminateSession(sessionId, "expired_on_access") }
            return null
        }
        return state.meta
    }

    suspend fun appendToSession(
        sessionId: String,
        accountId: AccountId,
        moduleId: ModuleId,
        requestOffset: Long,
        channel: ByteReadChannel,
        maxChunkBytes: Long,
    ): AppendResult {
        val state = sessions[sessionId] ?: return AppendResult.SessionNotFound
        if (!state.meta.matchesScope(accountId, moduleId)) return AppendResult.SessionNotFound

        return state.lock.withLock {
            val meta = state.meta
            if (meta.isExpired()) {
                terminateSessionLocked(state, "expired_on_append")
                return@withLock AppendResult.SessionNotActive("expired")
            }
            if (meta.state != UploadSessionMeta.State.ACTIVE) {
                return@withLock AppendResult.SessionNotActive(meta.state.name)
            }
            if (requestOffset != meta.offsetBytes) {
                return@withLock AppendResult.OffsetMismatch(meta.offsetBytes)
            }

            val partFile = state.sessionDir.resolve(PART_FILENAME)
            var bytesWritten = 0L
            val remaining = meta.expectedSizeBytes - meta.offsetBytes

            // RandomAccessFile("rw") does NOT truncate — critical for resumable uploads.
            // FileOutputStream(file) would truncate on open and zero-fill earlier bytes.
            RandomAccessFile(partFile.toFile(), "rw").use { raf ->
                raf.seek(meta.offsetBytes)
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read <= 0) break

                    if (bytesWritten + read > remaining) {
                        return@withLock AppendResult.SizeExceeded
                    }
                    if (bytesWritten + read > maxChunkBytes) {
                        return@withLock AppendResult.ChunkTooLarge
                    }

                    raf.write(buffer, 0, read)
                    bytesWritten += read
                }
            }

            val newOffset = meta.offsetBytes + bytesWritten
            val updatedMeta = meta.copy(
                offsetBytes = newOffset,
                lastActivityAt = Instant.now(),
            )
            persistSessionMeta(state.sessionDir, updatedMeta)
            sessions[sessionId] = state.copy(meta = updatedMeta)

            log(TAG, VERBOSE) { "append: session=$sessionId, wrote=$bytesWritten, newOffset=$newOffset" }
            AppendResult.Success(newOffset)
        }
    }

    suspend fun finalizeSession(
        sessionId: String,
        accountId: AccountId,
        moduleId: ModuleId,
        hashAlgorithm: String?,
        hashHex: String?,
    ): FinalizeResult {
        val state = sessions[sessionId] ?: return FinalizeResult.SessionNotFound
        if (!state.meta.matchesScope(accountId, moduleId)) return FinalizeResult.SessionNotFound

        return state.lock.withLock {
            val meta = state.meta

            if (meta.isExpired()) {
                terminateSessionLocked(state, "expired_on_finalize")
                return@withLock FinalizeResult.SessionNotFound
            }

            // Idempotent: already complete
            if (meta.state == UploadSessionMeta.State.COMPLETE) {
                return@withLock FinalizeResult.AlreadyComplete(meta.blobId, meta.expectedSizeBytes)
            }

            if (meta.state != UploadSessionMeta.State.ACTIVE && meta.state != UploadSessionMeta.State.UPLOADED) {
                return@withLock FinalizeResult.SessionNotFound
            }

            if (meta.offsetBytes != meta.expectedSizeBytes) {
                return@withLock FinalizeResult.NotFullyUploaded(meta.offsetBytes, meta.expectedSizeBytes)
            }

            val effectiveAlgorithm = hashAlgorithm ?: meta.hashAlgorithm
            val effectiveHex = hashHex ?: meta.hashHex

            if (meta.hashHex != null && hashHex != null && meta.hashHex != hashHex) {
                return@withLock FinalizeResult.ChecksumConflict
            }
            if (effectiveHex == null) {
                return@withLock FinalizeResult.MissingChecksum
            }

            if (meta.expectedSizeBytes > 0) {
                val partFile = state.sessionDir.resolve(PART_FILENAME)
                val actualHash = computeSha256(partFile)
                if (actualHash != effectiveHex) {
                    log(TAG, WARN) { "finalize: checksum mismatch for session=$sessionId" }
                    return@withLock FinalizeResult.ChecksumMismatch
                }
            }

            // Rename payload.part to payload.blob (stays in sessions/ until commit)
            val partFile = state.sessionDir.resolve(PART_FILENAME)
            val blobFile = state.sessionDir.resolve(BLOB_FILENAME)
            if (partFile.exists()) {
                partFile.moveTo(blobFile, overwrite = true)
            }

            val updatedMeta = meta.copy(
                state = UploadSessionMeta.State.COMPLETE,
                hashAlgorithm = effectiveAlgorithm,
                hashHex = effectiveHex,
                lastActivityAt = Instant.now(),
            )
            persistSessionMeta(state.sessionDir, updatedMeta)
            sessions[sessionId] = state.copy(meta = updatedMeta)

            log(TAG) { "finalize: session=$sessionId complete, blob=${meta.blobId}" }
            FinalizeResult.Success(meta.blobId, meta.expectedSizeBytes)
        }
    }

    // endregion

    // region Abort & Terminate

    suspend fun abortSession(sessionId: String, accountId: AccountId, moduleId: ModuleId): UploadSessionMeta? {
        val state = sessions[sessionId] ?: return null
        if (!state.meta.matchesScope(accountId, moduleId)) return null
        return terminateSession(sessionId, "aborted")
    }

    /**
     * Terminates a session: acquires lock, marks terminal, deletes files, removes from map, releases quota.
     */
    suspend fun terminateSession(sessionId: String, reason: String): UploadSessionMeta? {
        val state = sessions[sessionId] ?: return null
        return state.lock.withLock {
            terminateSessionLocked(state, reason)
        }
    }

    private fun terminateSessionLocked(state: SessionState, reason: String): UploadSessionMeta {
        val meta = state.meta
        sessions.remove(meta.sessionId)
        try {
            state.sessionDir.deleteRecursively()
        } catch (e: Exception) {
            log(TAG, WARN) { "terminateSession(${meta.sessionId}): failed to delete dir: ${e.message}" }
        }
        if (meta.expectedSizeBytes > 0 && meta.state != UploadSessionMeta.State.ABORTED && meta.state != UploadSessionMeta.State.EXPIRED) {
            storageTracker.releaseReservation(meta.accountId, meta.expectedSizeBytes)
        }
        log(TAG) { "terminateSession(${meta.sessionId}): $reason" }
        return meta
    }

    /**
     * Aborts all sessions for a given module scope. Used by legacy POST and DELETE.
     */
    suspend fun abortSessionsForModule(accountId: AccountId, deviceId: DeviceId, moduleId: ModuleId) {
        val toAbort = sessions.values.filter {
            it.meta.accountId == accountId && it.meta.deviceId == deviceId && it.meta.moduleId == moduleId
        }
        for (state in toAbort) {
            terminateSession(state.meta.sessionId, "module_write_or_delete")
        }
    }

    /**
     * Aborts all sessions for a device. Used by device deletion.
     */
    suspend fun abortSessionsForDevice(accountId: AccountId, deviceId: DeviceId) {
        val toAbort = sessions.values.filter {
            it.meta.accountId == accountId && it.meta.deviceId == deviceId
        }
        for (state in toAbort) {
            terminateSession(state.meta.sessionId, "device_deleted")
        }
    }

    /**
     * Aborts all sessions for an account. Used by account deletion.
     */
    suspend fun abortSessionsForAccount(accountId: AccountId) {
        val toAbort = sessions.values.filter { it.meta.accountId == accountId }
        for (state in toAbort) {
            terminateSession(state.meta.sessionId, "account_deleted")
        }
    }

    // endregion

    // region Lookups (scoped)

    sealed interface ConsumeResult {
        data class Ready(val meta: UploadSessionMeta, val file: java.io.File) : ConsumeResult
        data object SessionNotFound : ConsumeResult
        data object PayloadMissing : ConsumeResult
    }

    /**
     * Atomically resolves a COMPLETE upload session scoped to this account/device/module and
     * verifies its staged payload file still exists. Returned [ConsumeResult.Ready] holds both the
     * session metadata (for building the live [BlobRef]) and the on-disk file (to be moved into
     * the module's live blob directory).
     */
    fun consumeCompletedBlob(blobId: String, accountId: AccountId, deviceId: DeviceId, moduleId: ModuleId): ConsumeResult {
        val state = sessions.values
            .firstOrNull {
                it.meta.blobId == blobId
                    && it.meta.state == UploadSessionMeta.State.COMPLETE
                    && it.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)
            }
            ?: return ConsumeResult.SessionNotFound
        val blobFile = state.sessionDir.resolve(BLOB_FILENAME)
        if (!blobFile.exists()) return ConsumeResult.PayloadMissing
        return ConsumeResult.Ready(meta = state.meta, file = blobFile.toFile())
    }

    fun removeCommittedSessionByBlobId(blobId: String) {
        val entry = sessions.entries.firstOrNull { it.value.meta.blobId == blobId } ?: return
        val state = sessions.remove(entry.key) ?: return
        try {
            state.sessionDir.deleteRecursively()
        } catch (e: Exception) {
            log(TAG, WARN) { "removeCommittedSession: failed to clean up ${entry.key}: ${e.message}" }
        }
    }

    /**
     * Checks whether a module has any active non-expired sessions (for GC protection).
     */
    fun hasActiveSessionsForModule(accountId: AccountId, deviceId: DeviceId, moduleId: ModuleId): Boolean {
        return sessions.values.any {
            it.meta.accountId == accountId && it.meta.deviceId == deviceId && it.meta.moduleId == moduleId
                && !it.meta.isExpired()
                && (it.meta.state == UploadSessionMeta.State.ACTIVE || it.meta.state == UploadSessionMeta.State.COMPLETE)
        }
    }

    // endregion

    // region Internals

    private fun persistSessionMeta(sessionDir: Path, meta: UploadSessionMeta) {
        val metaFile = sessionDir.resolve(SESSION_META_FILENAME)
        val tempFile = sessionDir.resolve("${SESSION_META_FILENAME}.tmp")
        tempFile.writeText(serializer.encodeToString(meta))
        tempFile.moveTo(metaFile, overwrite = true)
    }

    private fun computeSha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.toFile().inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // endregion

    companion object {
        internal const val PART_FILENAME = "payload.part"
        internal const val BLOB_FILENAME = "payload.blob"
        internal const val SESSION_META_FILENAME = "session.json"
        private val TAG = logTag("Upload", "Session", "Repo")
    }
}

class SessionLimitExceededException(val limit: Int) :
    RuntimeException("Active session limit exceeded: max $limit per device")
