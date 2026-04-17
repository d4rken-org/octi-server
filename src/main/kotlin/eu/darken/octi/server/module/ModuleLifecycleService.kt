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
    }

    sealed interface CommitResult {
        data class Success(val etag: String) : CommitResult
        data class PreconditionFailed(val message: String) : CommitResult
        data class BadRequest(val message: String) : CommitResult
    }

    /**
     * Legacy POST write — under device lock, loads old meta, rejects if blob-backed,
     * writes payload, adjusts quota, aborts scoped sessions.
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
            val newMeta = moduleRepo.writeUnlocked(caller, target, moduleId, write)
            val delta = newMeta.documentSizeBytes - oldDocSize
            if (delta != 0L) {
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

            // Resolve blob refs
            val newBlobRefs = mutableListOf<BlobRef>()
            for (blobId in blobRefIds) {
                val existingRef = existingMeta?.blobRefs?.find { it.blobId == blobId }
                if (existingRef != null) {
                    newBlobRefs.add(existingRef)
                    continue
                }
                val sessionMeta = sessionRepo.getCompletedSessionByBlobId(blobId, caller.accountId, target.id, moduleId)
                    ?: return@withLock CommitResult.BadRequest("Referenced blobId not found: $blobId") to emptyList<Path>()
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

            val modulePath = moduleRepo.resolveModulePath(target, moduleId)

            // Ensure directory exists
            modulePath.apply {
                if (!parent.exists()) parent.createDirectory()
                if (!exists()) createDirectory()
            }

            // Move finalized blobs from sessions/ to blobs/
            for (ref in newBlobRefs) {
                val isNewBlob = existingMeta?.blobRefs?.any { it.blobId == ref.blobId } != true
                if (isNewBlob) {
                    val sessionBlobFile = sessionRepo.getCompletedBlobFileByBlobId(ref.blobId, caller.accountId, target.id, moduleId)
                        ?: return@withLock CommitResult.BadRequest("Staged blob payload missing for ${ref.blobId}") to emptyList<Path>()
                    val prefix = ref.storageKey.take(4)
                    val liveBlobDir = modulePath.resolve("blobs").resolve(prefix).resolve(ref.storageKey)
                    liveBlobDir.createDirectories()
                    sessionBlobFile.toPath().moveTo(liveBlobDir.resolve("payload.blob"), overwrite = true)
                }
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

            // Quota update
            val newReferencedBytes = newBlobRefs.filter { ref ->
                existingMeta?.blobRefs?.any { it.blobId == ref.blobId } != true
            }.sumOf { it.sizeBytes }
            val orphanedBlobRefs = existingMeta?.blobRefs
                ?.filter { old -> newBlobRefs.none { it.blobId == old.blobId } }
                ?: emptyList()
            val orphanedBytes = orphanedBlobRefs.sumOf { it.sizeBytes }
            val docDelta = documentBytes.size.toLong() - (existingMeta?.documentSizeBytes ?: 0L)

            if (newReferencedBytes > 0 || orphanedBytes > 0) {
                storageTracker.commitReservation(caller.accountId, newReferencedBytes, orphanedBytes)
            }
            if (docDelta != 0L) {
                storageTracker.adjustUsed(caller.accountId, docDelta)
            }

            // Collect orphaned blob paths for async deletion outside the lock —
            // deleting here would block every concurrent read/write for large orphans.
            val orphansToDelete = orphanedBlobRefs.map { orphan ->
                val prefix = orphan.storageKey.take(4)
                modulePath.resolve("blobs").resolve(prefix).resolve(orphan.storageKey)
            }

            log(TAG) { "commitModule(${caller.id.shortId()}): $moduleId committed, etag=$newEtag" }
            CommitResult.Success(newEtag) to orphansToDelete
        }

        // Fire-and-forget orphan cleanup on AppScope. StartupRecoveryService sweeps any
        // stragglers on next boot, so a crash here is not load-bearing.
        if (orphanPaths.isNotEmpty()) {
            appScope.launch {
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
     */
    suspend fun deleteForDevice(accountId: AccountId, target: Device) {
        target.sync.withLock {
            // Calculate total used bytes before deletion
            val modulesPath = target.path.resolve("modules")
            if (modulesPath.exists()) {
                modulesPath.listDirectoryEntries().forEach { moduleDir ->
                    val meta = moduleRepo.loadMeta(target, resolveModuleId(moduleDir) ?: return@forEach)
                    if (meta != null) {
                        val total = meta.documentSizeBytes + meta.blobRefs.sumOf { it.sizeBytes }
                        if (total > 0) storageTracker.adjustUsed(accountId, -total)
                    }
                }
            }
            moduleRepo.clearUnlocked(target)
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

    private fun resolveModuleId(moduleDir: java.nio.file.Path): String? {
        val metaFile = moduleDir.resolve("module.json")
        if (!metaFile.exists()) return null
        return try {
            val text = metaFile.readText()
            if (ModuleRepo.isV1Meta(text)) {
                json.decodeFromString<ModuleMeta>(text).moduleId
            } else {
                json.decodeFromString<Module.Info>(text).id
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val TAG = logTag("Module", "Lifecycle")
    }
}
