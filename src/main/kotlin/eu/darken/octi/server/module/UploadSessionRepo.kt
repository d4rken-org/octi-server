package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.launchPeriodicJob
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
    private val accountLocks = ConcurrentHashMap<AccountId, Mutex>()

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
        appScope.launchPeriodicJob(
            tag = TAG,
            interval = config.moduleGCInterval,
            initialDelay = config.moduleGCInterval,
            onErrorMessage = "Session GC failed",
        ) {
            reapExpiredSessions()
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

    suspend fun createSession(
        accountId: AccountId,
        deviceId: DeviceId,
        moduleId: ModuleId,
        expectedSizeBytes: Long,
        hashAlgorithm: String?,
        hashHex: String?,
    ): UploadSessionMeta = lockForAccount(accountId).withLock {
        // Both ACTIVE and COMPLETE sessions count toward the cap. COMPLETE sessions still
        // hold reservedBytes until their PUT-commit (or expiry), and pre-fix only ACTIVE was
        // counted — letting an attacker create-finalize-repeat to accumulate COMPLETE.
        val countsTowardCap: (SessionState) -> Boolean = { s ->
            (s.meta.state == UploadSessionMeta.State.ACTIVE || s.meta.state == UploadSessionMeta.State.COMPLETE)
                && !s.meta.isExpired()
        }
        val perAccountCount = sessions.values.count {
            it.meta.accountId == accountId && countsTowardCap(it)
        }
        if (perAccountCount >= config.maxActiveUploadSessionsPerAccount) {
            throw SessionLimitExceededException(config.maxActiveUploadSessionsPerAccount)
        }
        val perDeviceCount = sessions.values.count {
            it.meta.accountId == accountId && it.meta.deviceId == deviceId && countsTowardCap(it)
        }
        if (perDeviceCount >= config.maxActiveUploadSessionsPerDevice) {
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
            completeIdleTtlSeconds = config.completeIdleTtlSeconds,
            state = UploadSessionMeta.State.ACTIVE,
        )

        persistSessionMeta(sessionDir, meta)
        sessionDir.resolve(PART_FILENAME).createFile()
        sessions[sessionId] = SessionState(meta = meta, sessionDir = sessionDir)

        log(TAG) { "createSession: session=$sessionId, blob=$blobId, size=$expectedSizeBytes" }
        meta
    }

    /**
     * Gets session metadata with scope validation and synchronous expiry check.
     */
    fun getSession(sessionId: String, accountId: AccountId, deviceId: DeviceId, moduleId: ModuleId): UploadSessionMeta? {
        val state = sessions[sessionId] ?: return null
        if (!state.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)) return null
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
        deviceId: DeviceId,
        moduleId: ModuleId,
        requestOffset: Long,
        channel: ByteReadChannel,
        maxChunkBytes: Long,
    ): AppendResult {
        val state = sessions[sessionId] ?: return AppendResult.SessionNotFound
        if (!state.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)) return AppendResult.SessionNotFound

        return state.lock.withLock {
            // Re-read current state under the lock — `state` was captured before lock
            // acquisition and `state.meta` may be stale (a concurrent caller could have
            // updated `sessions[sessionId] = state.copy(meta = newMeta)` while we waited).
            // The `Mutex` is preserved by `data class.copy`, so the lock we hold is the
            // right one; only the meta needs refreshing.
            val current = sessions[sessionId] ?: return@withLock AppendResult.SessionNotFound
            val meta = current.meta
            if (meta.isExpired()) {
                terminateSessionLocked(current, "expired_on_append")
                return@withLock AppendResult.SessionNotActive("expired")
            }
            if (meta.state != UploadSessionMeta.State.ACTIVE) {
                return@withLock AppendResult.SessionNotActive(meta.state.name)
            }
            if (requestOffset != meta.offsetBytes) {
                return@withLock AppendResult.OffsetMismatch(meta.offsetBytes)
            }

            val partFile = current.sessionDir.resolve(PART_FILENAME)
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
                        raf.setLength(meta.offsetBytes)
                        return@withLock AppendResult.SizeExceeded
                    }
                    if (bytesWritten + read > maxChunkBytes) {
                        raf.setLength(meta.offsetBytes)
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
            persistSessionMeta(current.sessionDir, updatedMeta)
            sessions[sessionId] = current.copy(meta = updatedMeta)

            log(TAG, VERBOSE) { "append: session=$sessionId, wrote=$bytesWritten, newOffset=$newOffset" }
            AppendResult.Success(newOffset)
        }
    }

    suspend fun finalizeSession(
        sessionId: String,
        accountId: AccountId,
        deviceId: DeviceId,
        moduleId: ModuleId,
        hashAlgorithm: String?,
        hashHex: String?,
    ): FinalizeResult {
        val state = sessions[sessionId] ?: return FinalizeResult.SessionNotFound
        if (!state.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)) return FinalizeResult.SessionNotFound

        return state.lock.withLock {
            // Re-read current state under the lock so we observe any meta updates that
            // raced us to the lock. The Mutex itself is preserved by data class copy.
            val current = sessions[sessionId] ?: return@withLock FinalizeResult.SessionNotFound
            val meta = current.meta

            if (meta.isExpired()) {
                terminateSessionLocked(current, "expired_on_finalize")
                return@withLock FinalizeResult.SessionNotFound
            }

            // Idempotent: already complete
            if (meta.state == UploadSessionMeta.State.COMPLETE) {
                return@withLock FinalizeResult.AlreadyComplete(meta.blobId, meta.expectedSizeBytes)
            }

            if (meta.state != UploadSessionMeta.State.ACTIVE) {
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
            // Defensive: hashHex without hashAlgorithm produces a session shape that
            // recovery rejects on next restart (StartupRecoveryService.hasValidCompleteHash
            // requires non-null algorithm). Reject here too in case a pre-fix session
            // persisted that combo on disk and is now finalizing.
            if (effectiveAlgorithm == null) {
                return@withLock FinalizeResult.MissingChecksum
            }

            val partFile = current.sessionDir.resolve(PART_FILENAME)
            if (!partFile.exists()) return@withLock FinalizeResult.ChecksumMismatch
            val actualHash = computeSha256(partFile)
            if (actualHash != effectiveHex) {
                log(TAG, WARN) { "finalize: checksum mismatch for session=$sessionId" }
                return@withLock FinalizeResult.ChecksumMismatch
            }

            // Rename payload.part to payload.blob (stays in sessions/ until commit)
            val blobFile = current.sessionDir.resolve(BLOB_FILENAME)
            if (partFile.exists()) {
                partFile.moveTo(blobFile, overwrite = true)
            }

            // After finalize the only legitimate next step is the client's module PUT — there is
            // no slow-network upload reason to keep the session for the full ACTIVE-state idle TTL.
            // Shorten the idle window so cancel-after-finalize / client-crash orphans are reaped
            // within minutes instead of an hour. A periodic GC tick still applies on top of this.
            val updatedMeta = meta.copy(
                state = UploadSessionMeta.State.COMPLETE,
                hashAlgorithm = effectiveAlgorithm,
                hashHex = effectiveHex,
                lastActivityAt = Instant.now(),
            )
            persistSessionMeta(current.sessionDir, updatedMeta)
            sessions[sessionId] = current.copy(meta = updatedMeta)

            log(TAG) { "finalize: session=$sessionId complete, blob=${meta.blobId}" }
            FinalizeResult.Success(meta.blobId, meta.expectedSizeBytes)
        }
    }

    // endregion

    // region Abort & Terminate

    suspend fun abortSession(sessionId: String, accountId: AccountId, deviceId: DeviceId, moduleId: ModuleId): UploadSessionMeta? {
        val state = sessions[sessionId] ?: return null
        if (!state.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)) return null
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
        // Idempotent: another path (GC vs in-flight call) may have already terminated this
        // session under the same lock. Both paths share the same `Mutex` (data class copy
        // preserves it), so the second caller sees an already-removed map entry here.
        if (sessions.remove(meta.sessionId) == null) return meta
        // Skip releaseReservation when the staged payload is already gone — that means a
        // successful commit already moved the file out via consumeAndMoveCompletedBlob, and
        // commitReservation in the storage tracker is responsible for the reserved→used
        // transition. Releasing here would double-debit reservedBytes.
        val stagedExists = state.sessionDir.resolve(BLOB_FILENAME).exists() ||
            state.sessionDir.resolve(PART_FILENAME).exists()
        try {
            state.sessionDir.deleteRecursively()
        } catch (e: Exception) {
            log(TAG, WARN) { "terminateSession(${meta.sessionId}): failed to delete dir: ${e.message}" }
        }
        // Sweep the parent sessions/ and modules/{hash}/ dirs if they're now empty.
        // createSession created modules/{hash}/ as a side effect; without this sweep an
        // attacker spamming sessions for distinct moduleIds would leave behind one empty
        // module dir per session and pile up dirents.
        cleanupEmptyParentsAfterSession(state.sessionDir)
        if (stagedExists && meta.expectedSizeBytes > 0 && meta.state != UploadSessionMeta.State.ABORTED) {
            storageTracker.releaseReservation(meta.accountId, meta.expectedSizeBytes)
        }
        log(TAG) { "terminateSession(${meta.sessionId}): $reason" }
        return meta
    }

    private fun cleanupEmptyParentsAfterSession(sessionDir: Path) {
        // Walk up two levels: sessions/ then modules/{hash}/. Stop on first non-empty parent.
        val sessionsDir = sessionDir.parent ?: return
        if (!deleteIfEmpty(sessionsDir)) return
        val moduleDir = sessionsDir.parent ?: return
        deleteIfEmpty(moduleDir)
    }

    private fun deleteIfEmpty(dir: Path): Boolean {
        return try {
            if (!dir.exists()) return true
            if (dir.listDirectoryEntries().isNotEmpty()) return false
            dir.deleteIfExists()
            true
        } catch (e: Exception) {
            // DirectoryNotEmptyException can happen if a sibling created a child between
            // our check and delete. Either way, leaving the dir is acceptable.
            log(TAG, VERBOSE) { "deleteIfEmpty($dir): ${e.message}" }
            false
        }
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
        data class Ready(val meta: UploadSessionMeta) : ConsumeResult
        data object SessionNotFound : ConsumeResult
        data object PayloadMissing : ConsumeResult
    }

    /**
     * Atomically resolves a COMPLETE upload session scoped to this account/device/module,
     * verifies its staged payload file still exists, and moves the file into the module's
     * live blob dir — all under the session's lock so the GC reaper can't delete the staged
     * file between resolve and move.
     *
     * [destinationFor] computes the live destination path from the session metadata; called
     * inside the lock with the validated meta. Parent directories are created automatically.
     *
     * Pre-fix design returned the file path so the caller could move it outside the lock,
     * leaving a window where GC could terminate the session and delete the payload before
     * commitModule got around to moving it. The atomic API closes that window.
     */
    suspend fun consumeAndMoveCompletedBlob(
        blobId: String,
        accountId: AccountId,
        deviceId: DeviceId,
        moduleId: ModuleId,
        destinationFor: (UploadSessionMeta) -> Path,
    ): ConsumeResult {
        val state = sessions.values
            .firstOrNull {
                it.meta.blobId == blobId
                    && it.meta.state == UploadSessionMeta.State.COMPLETE
                    && it.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)
            }
            ?: return ConsumeResult.SessionNotFound

        return state.lock.withLock {
            // Re-read current under the lock — `state.meta` was captured before lock
            // acquisition. Also re-validates against GC racing in between.
            val current = sessions[state.meta.sessionId] ?: return@withLock ConsumeResult.SessionNotFound
            if (current.meta.state != UploadSessionMeta.State.COMPLETE) return@withLock ConsumeResult.SessionNotFound
            if (current.meta.isExpired()) return@withLock ConsumeResult.SessionNotFound

            val blobFile = current.sessionDir.resolve(BLOB_FILENAME)
            if (!blobFile.exists()) return@withLock ConsumeResult.PayloadMissing

            val destPath = destinationFor(current.meta)
            destPath.parent.createDirectories()
            blobFile.moveTo(destPath, overwrite = true)
            // The session entry stays in the map; commitModule's removeCommittedSessionByBlobId
            // cleans it up after the module.json rename. terminateSessionLocked sees the empty
            // session dir (staged file is gone) and skips releaseReservation — commitReservation
            // owns the reserved→used transition.
            ConsumeResult.Ready(meta = current.meta)
        }
    }

    /**
     * Validates that a COMPLETE upload session for [blobId] exists, is scoped correctly,
     * and has its staged payload on disk. Used by commit's pre-validation pass to fail
     * fast before any blob is moved (see ModuleLifecycleService.commitModule). Does not
     * mutate state. TOCTOU: a GC reaper or abort can still terminate the session between
     * peek and the subsequent consumeAndMoveCompletedBlob call — the commit path must
     * still handle that failure with a rollback.
     */
    suspend fun peekCompletedBlob(
        blobId: String,
        accountId: AccountId,
        deviceId: DeviceId,
        moduleId: ModuleId,
    ): PeekResult {
        val state = sessions.values
            .firstOrNull {
                it.meta.blobId == blobId
                    && it.meta.state == UploadSessionMeta.State.COMPLETE
                    && it.meta.matchesScopeWithDevice(accountId, deviceId, moduleId)
            }
            ?: return PeekResult.NotFound

        return state.lock.withLock {
            val current = sessions[state.meta.sessionId] ?: return@withLock PeekResult.NotFound
            if (current.meta.state != UploadSessionMeta.State.COMPLETE) return@withLock PeekResult.NotFound
            if (current.meta.isExpired()) return@withLock PeekResult.NotFound
            if (!current.sessionDir.resolve(BLOB_FILENAME).exists()) return@withLock PeekResult.PayloadMissing
            PeekResult.Ready
        }
    }

    sealed interface PeekResult {
        data object Ready : PeekResult
        data object NotFound : PeekResult
        data object PayloadMissing : PeekResult
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

    fun sessionCountsByState(): Map<UploadSessionMeta.State, Int> {
        return sessions.values.groupingBy { it.meta.state }.eachCount()
    }

    // endregion

    // region Internals

    private fun persistSessionMeta(sessionDir: Path, meta: UploadSessionMeta) {
        val metaFile = sessionDir.resolve(SESSION_META_FILENAME)
        val tempFile = sessionDir.resolve("${SESSION_META_FILENAME}.tmp")
        tempFile.writeText(serializer.encodeToString(meta))
        tempFile.moveTo(metaFile, overwrite = true)
    }

    private fun lockForAccount(accountId: AccountId): Mutex {
        return accountLocks.computeIfAbsent(accountId) { Mutex() }
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
