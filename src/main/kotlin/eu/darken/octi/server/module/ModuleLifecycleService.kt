package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@Singleton
class ModuleLifecycleService @Inject constructor(
    private val appScope: AppScope,
    private val config: App.Config,
    private val moduleRepo: ModuleRepo,
    private val sessionRepo: UploadSessionRepo,
    private val storageTracker: AccountStorageTracker,
    private val json: Json,
) {

    sealed interface LegacyWriteResult {
        data class Success(val meta: ModuleMeta) : LegacyWriteResult
        data object BlobBacked : LegacyWriteResult
        data object QuotaExceeded : LegacyWriteResult
    }

    sealed interface CommitResult {
        data class Success(val etag: String) : CommitResult
        data class PreconditionFailed(val message: String) : CommitResult
        data class BadRequest(val message: String) : CommitResult
        data object QuotaExceeded : CommitResult
    }

    sealed interface DeleteBlobResult {
        data class Success(val newEtag: String, val removedSizeBytes: Long) : DeleteBlobResult
        data object ModuleNotFound : DeleteBlobResult
        data object BlobNotFound : DeleteBlobResult
        data class EtagMismatch(val currentEtag: String) : DeleteBlobResult
    }

    /**
     * Legacy POST write — under device lock, loads old meta, rejects if blob-backed,
     * pre-checks the document quota delta, writes payload, settles quota, aborts
     * scoped sessions. Quota is reserved before disk I/O so a write that would
     * exceed the cap returns 507 without touching the filesystem.
     */
    suspend fun legacyWrite(
        caller: Device,
        target: Device,
        moduleId: ModuleId,
        write: Module.Write,
    ): LegacyWriteResult {
        return target.sync.withLock {
            val oldMeta = moduleRepo.loadMeta(target, moduleId)
            if (oldMeta != null && oldMeta.blobRefs.isNotEmpty()) {
                return@withLock LegacyWriteResult.BlobBacked
            }

            val oldDocSize = oldMeta?.documentSizeBytes ?: 0L
            val delta = write.size.toLong() - oldDocSize
            if (delta > 0 && !storageTracker.tryAdjustUsed(caller.accountId, delta)) {
                return@withLock LegacyWriteResult.QuotaExceeded
            }

            val newMeta = try {
                moduleRepo.writeUnlocked(caller, target, moduleId, write)
            } catch (e: Exception) {
                if (delta > 0) storageTracker.adjustUsed(caller.accountId, -delta)
                throw e
            }
            // Negative delta (smaller payload) is freed only after write succeeds.
            if (delta < 0) {
                storageTracker.adjustUsed(caller.accountId, delta)
            }

            // Abort outstanding sessions for this module (plan requirement)
            sessionRepo.abortSessionsForModule(caller.accountId, target.id, moduleId)

            LegacyWriteResult.Success(newMeta)
        }
    }

    /**
     * Legacy DELETE — under device lock, loads meta, computes total bytes, deletes,
     * adjusts quota, aborts scoped sessions.
     *
     * Unlike [legacyWrite], this deliberately does **not** reject blob-backed modules:
     * DELETE is an explicit, unambiguous request to remove a module and everything it
     * references. A client that understands blobs and wants to drop one without losing
     * the rest must use PUT commit with an updated `blobRefs` list instead. Clients that
     * don't understand blobs still need an escape hatch to wipe a module outright.
     */
    suspend fun legacyDelete(
        caller: Device,
        target: Device,
        moduleId: ModuleId,
    ) {
        target.sync.withLock {
            val oldMeta = moduleRepo.loadMeta(target, moduleId)
            if (oldMeta != null) {
                val totalBytes = oldMeta.documentSizeBytes + oldMeta.blobRefs.sumOf { it.sizeBytes }
                moduleRepo.deleteUnlocked(target, moduleId)
                if (totalBytes > 0) {
                    storageTracker.adjustUsed(caller.accountId, -totalBytes)
                }
            } else {
                moduleRepo.deleteUnlocked(target, moduleId)
            }

            sessionRepo.abortSessionsForModule(caller.accountId, target.id, moduleId)
        }
    }

    /**
     * Single-blob delete — under device lock, validates If-Match, removes the blobRef,
     * persists new metadata, releases quota. The on-disk blob directory is cleaned up
     * asynchronously outside the lock; startup orphan sweep reclaims it if we crash.
     *
     * Staged upload sessions are untouched: their blobIds are distinct from committed
     * ones and have their own expiry path.
     */
    suspend fun deleteBlob(
        caller: Device,
        target: Device,
        moduleId: ModuleId,
        blobId: String,
        ifMatch: String,
    ): DeleteBlobResult {
        val (outcome, orphanPath) = target.sync.withLock {
            when (val result = moduleRepo.removeBlobRefUnlocked(caller, target, moduleId, blobId, ifMatch)) {
                is RemoveBlobRefResult.ModuleNotFound -> DeleteBlobResult.ModuleNotFound to null
                is RemoveBlobRefResult.BlobNotFound -> DeleteBlobResult.BlobNotFound to null
                is RemoveBlobRefResult.EtagMismatch -> DeleteBlobResult.EtagMismatch(result.currentEtag) to null
                is RemoveBlobRefResult.Success -> {
                    val removed = result.removed
                    if (removed.blobRef.sizeBytes > 0) {
                        storageTracker.adjustUsed(caller.accountId, -removed.blobRef.sizeBytes)
                    }
                    DeleteBlobResult.Success(removed.newMeta.etag, removed.blobRef.sizeBytes) to removed.orphanPath
                }
            }
        }

        if (orphanPath != null) {
            // Dispatchers.IO: deleteRecursively is blocking FS work; AppScope default is Dispatchers.Default.
            appScope.launch(Dispatchers.IO) {
                try {
                    orphanPath.deleteRecursively()
                } catch (e: Exception) {
                    log(TAG, WARN) { "deleteBlob: failed to delete orphan $orphanPath: ${e.message}" }
                }
            }
        }

        if (outcome is DeleteBlobResult.Success) {
            log(TAG) { "deleteBlob(${caller.id.shortId()}): $moduleId/$blobId (-${outcome.removedSizeBytes}B)" }
        }
        return outcome
    }

    /**
     * New PUT commit — under device lock, validates preconditions, resolves blobs,
     * writes atomically, adjusts quota, cleans up sessions and orphaned blobs.
     */
    suspend fun commitModule(
        caller: Device,
        target: Device,
        moduleId: ModuleId,
        documentBytes: ByteArray,
        blobRefIds: List<String>,
        ifMatch: String?,
        ifNoneMatch: String?,
    ): CommitResult {
        val (result, orphanPaths) = target.sync.withLock {
            val existingMeta = moduleRepo.loadMeta(target, moduleId)

            // Precondition checks
            val error = when {
                ifNoneMatch == "*" && existingMeta != null -> "Module already exists"
                ifNoneMatch == "*" -> null
                ifMatch != null && existingMeta == null -> "Module does not exist"
                ifMatch != null && existingMeta!!.etag != ifMatch -> "ETag mismatch"
                ifMatch != null -> null
                else -> "PUT requires If-Match or If-None-Match: *"
            }
            if (error != null) return@withLock CommitResult.PreconditionFailed(error) to emptyList<Path>()

            // Pre-check the document delta against quota *before* any disk I/O. Blob bytes are
            // already reserved at session creation; only the document needs an at-commit check.
            val docDelta = documentBytes.size.toLong() - (existingMeta?.documentSizeBytes ?: 0L)
            if (docDelta > 0 && !storageTracker.tryAdjustUsed(caller.accountId, docDelta)) {
                return@withLock CommitResult.QuotaExceeded to emptyList<Path>()
            }
            var rollbackDocDelta = docDelta > 0

            try {
                val modulePath = moduleRepo.resolveModulePath(target, moduleId)

                // Ensure directory exists so new blobs can be moved in place during resolution.
                modulePath.apply {
                    if (!parent.exists()) parent.createDirectory()
                    if (!exists()) createDirectory()
                }

                // Resolve blob refs. The staged payload move is done atomically inside the
                // session lock by consumeAndMoveCompletedBlob — see its docstring for the race
                // it closes (GC reaping the session between lookup and move).
                val newBlobRefs = mutableListOf<BlobRef>()
                for (blobId in blobRefIds) {
                    val existingRef = existingMeta?.blobRefs?.find { it.blobId == blobId }
                    if (existingRef != null) {
                        newBlobRefs.add(existingRef)
                        continue
                    }
                    val consumed = sessionRepo.consumeAndMoveCompletedBlob(
                        blobId = blobId,
                        accountId = caller.accountId,
                        deviceId = target.id,
                        moduleId = moduleId,
                    ) { meta ->
                        val prefix = meta.storageKey.take(4)
                        modulePath.resolve("blobs").resolve(prefix).resolve(meta.storageKey).resolve("payload.blob")
                    }
                    val sessionMeta = when (consumed) {
                        is UploadSessionRepo.ConsumeResult.Ready -> consumed.meta
                        UploadSessionRepo.ConsumeResult.SessionNotFound ->
                            return@withLock CommitResult.BadRequest("Referenced blobId not found: $blobId") to emptyList<Path>()
                        UploadSessionRepo.ConsumeResult.PayloadMissing ->
                            return@withLock CommitResult.BadRequest("Staged blob payload missing for $blobId") to emptyList<Path>()
                    }
                    newBlobRefs.add(
                        BlobRef(
                            blobId = sessionMeta.blobId,
                            storageKey = sessionMeta.storageKey,
                            sizeBytes = sessionMeta.expectedSizeBytes,
                            hashAlgorithm = sessionMeta.hashAlgorithm,
                            hashHex = sessionMeta.hashHex,
                        )
                    )
                }

                // Write payload.blob first, then module.json as commit point
                val blobFile = modulePath.resolve("payload.blob")
                val tempBlob = modulePath.resolve("payload.blob.tmp")
                tempBlob.writeBytes(documentBytes)
                tempBlob.moveTo(blobFile, overwrite = true)

                val now = Instant.now()
                val newEtag = ModuleRepo.generateRandomEtag()
                val meta = ModuleMeta(
                    schemaVersion = 1,
                    moduleId = moduleId,
                    sourceDeviceId = caller.id,
                    etag = newEtag,
                    modifiedAt = now,
                    documentSizeBytes = documentBytes.size.toLong(),
                    blobRefs = newBlobRefs,
                )

                val metaFile = modulePath.resolve("module.json")
                val tempMeta = modulePath.resolve("module.json.tmp")
                tempMeta.writeText(json.encodeToString(meta))
                tempMeta.moveTo(metaFile, overwrite = true)

                // Update access metadata
                val accessFile = modulePath.resolve("access.json")
                val tempAccess = modulePath.resolve("access.json.tmp")
                tempAccess.writeText(json.encodeToString(AccessMeta(lastAccessedAt = now)))
                tempAccess.moveTo(accessFile, overwrite = true)

                // Clean up committed sessions
                for (ref in newBlobRefs) {
                    if (existingMeta?.blobRefs?.any { it.blobId == ref.blobId } != true) {
                        sessionRepo.removeCommittedSessionByBlobId(ref.blobId)
                    }
                }

                // Quota update for blobs and shrinking documents. Positive doc delta was
                // already applied by tryAdjustUsed above.
                val newReferencedBytes = newBlobRefs.filter { ref ->
                    existingMeta?.blobRefs?.any { it.blobId == ref.blobId } != true
                }.sumOf { it.sizeBytes }
                val orphanedBlobRefs = existingMeta?.blobRefs
                    ?.filter { old -> newBlobRefs.none { it.blobId == old.blobId } }
                    ?: emptyList()
                val orphanedBytes = orphanedBlobRefs.sumOf { it.sizeBytes }

                if (newReferencedBytes > 0 || orphanedBytes > 0) {
                    storageTracker.commitReservation(caller.accountId, newReferencedBytes, orphanedBytes)
                }
                if (docDelta < 0) {
                    storageTracker.adjustUsed(caller.accountId, docDelta)
                }
                rollbackDocDelta = false

                // Collect orphaned blob paths for async deletion outside the lock —
                // deleting here would block every concurrent read/write for large orphans.
                val orphansToDelete = orphanedBlobRefs.map { orphan ->
                    val prefix = orphan.storageKey.take(4)
                    modulePath.resolve("blobs").resolve(prefix).resolve(orphan.storageKey)
                }

                log(TAG) { "commitModule(${caller.id.shortId()}): $moduleId committed, etag=$newEtag" }
                CommitResult.Success(newEtag) to orphansToDelete
            } finally {
                if (rollbackDocDelta) {
                    storageTracker.adjustUsed(caller.accountId, -docDelta)
                }
            }
        }

        // Fire-and-forget orphan cleanup on AppScope. StartupRecoveryService sweeps any
        // stragglers on next boot, so a crash here is not load-bearing.
        // Dispatchers.IO: deleteRecursively is blocking FS work; AppScope default is Dispatchers.Default.
        if (orphanPaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                for (path in orphanPaths) {
                    try {
                        path.deleteRecursively()
                    } catch (e: Exception) {
                        log(TAG, WARN) { "commitModule: failed to delete orphaned blob $path: ${e.message}" }
                    }
                }
            }
        }

        return result
    }

    /**
     * Deletes all modules for a device, adjusts quota, aborts sessions.
     *
     * Aggregate bytes first via the same accounting helper recovery uses, then delete files,
     * then adjust quota once. This keeps quota and disk in agreement even when a module's
     * `module.json` is malformed or missing — recovery's payload.blob fallback applies here too.
     */
    suspend fun deleteForDevice(accountId: AccountId, target: Device) {
        target.sync.withLock {
            val modulesPath = target.path.resolve("modules")
            val totalBytes = if (modulesPath.exists()) {
                modulesPath.listDirectoryEntries().sumOf { moduleRepo.accountForModule(it).bytes }
            } else 0L
            moduleRepo.clearUnlocked(target)
            if (totalBytes > 0) storageTracker.adjustUsed(accountId, -totalBytes)
        }
        sessionRepo.abortSessionsForDevice(accountId, target.id)
    }

    /**
     * Cleans up all quota and session state for an account.
     */
    suspend fun deleteForAccount(accountId: AccountId) {
        sessionRepo.abortSessionsForAccount(accountId)
        storageTracker.removeAccount(accountId)
    }

    companion object {
        private val TAG = logTag("Module", "Lifecycle")
    }
}
