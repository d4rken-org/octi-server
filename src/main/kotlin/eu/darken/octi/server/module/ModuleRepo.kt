package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.AppScope
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.common.debug.logging.shortId
import eu.darken.octi.server.common.launchPeriodicJob
import eu.darken.octi.server.device.Device
import eu.darken.octi.server.device.DeviceRepo
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@Singleton
class ModuleRepo @Inject constructor(
    appScope: AppScope,
    private val config: App.Config,
    private val serializer: Json,
    private val deviceRepo: DeviceRepo,
    private val sessionRepo: dagger.Lazy<UploadSessionRepo>,
    private val storageTracker: AccountStorageTracker,
) {

    /**
     * In-memory shadow of per-module access times. Reads update the shadow unconditionally;
     * the disk `access.json` is written only when the last disk write is older than
     * [ACCESS_DEBOUNCE]. This absorbs the ~5–8 writes/min/module that blob downloads and
     * module reads would otherwise generate. The GC loop flushes pending shadow entries
     * before computing staleness, and reads prefer the shadow over disk.
     */
    private data class AccessState(val lastAccessedAt: Instant, val lastPersistedAt: Instant)

    private val accessShadow = ConcurrentHashMap<Path, AccessState>()

    init {
        appScope.launchPeriodicJob(
            tag = TAG,
            interval = config.moduleGCInterval,
            initialDelay = Duration.ofMillis(config.moduleGCInterval.toMillis() / 10),
            onErrorMessage = "Module cleanup failed",
        ) {
            deviceRepo.allDevices().forEach { device: Device ->
                device.sync.withLock {
                    try {
                        if (!device.modulesPath.exists()) return@withLock

                        flushPendingAccessWrites(device.modulesPath)

                        val now = Instant.now()
                        val staleModules = device.modulesPath.listDirectoryEntries().filter { path ->
                            val metaFile = path.resolve(META_FILENAME)
                            val accessFile = path.resolve(ACCESS_FILENAME)
                            // Session-only directory (no module.json, no access.json) — skip, session GC handles it
                            if (!metaFile.exists() && !accessFile.exists()) {
                                return@filter false
                            }

                            // Skip modules with active non-expired upload sessions
                            val moduleId = resolveModuleIdFromPath(path)
                            if (moduleId != null && sessionRepo.get().hasActiveSessionsForModule(device.accountId, device.id, moduleId)) {
                                return@filter false
                            }

                            val lastAccessed = readEffectiveAccessTime(path, metaFile, accessFile)
                                ?: return@filter false
                            Duration.between(lastAccessed, now) > config.moduleExpiration
                        }
                        if (staleModules.isNotEmpty()) {
                            log(TAG) { "Deleting ${staleModules.size} stale modules for ${device.id}" }
                            var totalReclaimed = 0L
                            staleModules.forEach {
                                val bytes = accountForModule(it).bytes
                                accessShadow.remove(it)
                                it.deleteRecursively()
                                totalReclaimed += bytes
                            }
                            if (totalReclaimed > 0) {
                                storageTracker.adjustUsed(device.accountId, -totalReclaimed)
                            }
                        }
                    } catch (e: IOException) {
                        log(TAG, ERROR) { "Module expiration check failed for $device\n${e.asLog()}" }
                    }
                }
            }
        }
    }

    /**
     * Effective last-access time for a module. Shadow (in-memory) is authoritative if present —
     * it is always at least as fresh as disk. Falls back to `access.json` on disk, then to
     * `module.json` mtime, then null if nothing readable.
     */
    private fun readEffectiveAccessTime(modulePath: Path, metaFile: Path, accessFile: Path): Instant? {
        accessShadow[modulePath]?.let { return it.lastAccessedAt }
        if (accessFile.exists()) {
            try {
                return serializer.decodeFromString<AccessMeta>(accessFile.readText()).lastAccessedAt
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to read $ACCESS_FILENAME for $modulePath, falling back to mtime: ${e.message}" }
            }
        }
        return try {
            metaFile.getLastModifiedTime().toInstant()
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to read mtime for $metaFile, skipping: ${e.message}" }
            null
        }
    }

    /**
     * Persists any pending shadow access times under the given `modulesPath`. Called by the GC
     * loop so that staleness computation and disk state don't diverge beyond one GC tick.
     */
    private fun flushPendingAccessWrites(modulesPath: Path) {
        for ((path, state) in accessShadow) {
            if (!path.startsWith(modulesPath)) continue
            if (state.lastAccessedAt > state.lastPersistedAt) {
                persistAccessMeta(path, state.lastAccessedAt)
            }
        }
    }

    /**
     * Bytes a module dir contributes to the quota tracker, plus the parsed v1 meta if any.
     *
     * Mirrors [StartupRecoveryService]'s accounting rule exactly so recovery and delete
     * paths agree by construction:
     *   - v1 `module.json` decodes → `documentSizeBytes + sum(blobRefs.sizeBytes)`, meta returned.
     *   - missing / malformed / legacy v0 → `payload.blob.fileSize()` if present, else 0; meta null.
     * Logs WARN on parse failure so the fallback isn't silent.
     */
    internal fun accountForModule(moduleDir: Path): ModuleAccounting {
        val metaFile = moduleDir.resolve(META_FILENAME)
        if (metaFile.exists()) {
            try {
                val text = metaFile.readText()
                if (isV1Meta(text)) {
                    val meta = serializer.decodeFromString<ModuleMeta>(text)
                    val total = meta.documentSizeBytes + meta.blobRefs.sumOf { it.sizeBytes }
                    return ModuleAccounting(bytes = total, v1Meta = meta)
                }
                // Legacy v0 meta: ignore document body declared in meta and count payload.blob.
            } catch (e: Exception) {
                log(TAG, WARN) { "accountForModule: failed to parse $metaFile: ${e.message}; falling back to payload.blob" }
            }
        }
        val blob = moduleDir.resolve(BLOB_FILENAME)
        val bytes = if (blob.exists()) blob.fileSize() else 0L
        return ModuleAccounting(bytes = bytes, v1Meta = null)
    }

    /**
     * Attempts to reverse-map a module directory path back to its moduleId.
     * Returns null if the directory name doesn't correspond to a known module.
     * This is best-effort — the SHA-1 hash isn't reversible, so we read module.json.
     */
    private fun resolveModuleIdFromPath(modulePath: Path): String? {
        val metaFile = modulePath.resolve(META_FILENAME)
        if (!metaFile.exists()) return null
        return try {
            val text = metaFile.readText()
            if (isV1Meta(text)) {
                serializer.decodeFromString<ModuleMeta>(text).moduleId
            } else {
                serializer.decodeFromString<Module.Info>(text).id
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun Device.getModulePath(moduleId: ModuleId): Path =
        modulesPath.resolve(moduleId.toModuleDirName())

    /**
     * Loads or synthesizes schema v1 [ModuleMeta] from disk.
     *
     * If `module.json` contains legacy `{ id, source }` format (no `schemaVersion`),
     * or is missing/unreadable while `payload.blob` exists, synthesizes v1 metadata
     * with a deterministic ETag and persists it (best-effort).
     *
     * Must be called under the device sync lock.
     */
    private fun loadOrMigrateMeta(modulePath: Path, moduleId: ModuleId): ModuleMeta? {
        val metaFile = modulePath.resolve(META_FILENAME)
        val blobFile = modulePath.resolve(BLOB_FILENAME)

        if (!modulePath.exists()) return null

        // Try to read existing metadata
        if (metaFile.exists()) {
            try {
                val text = metaFile.readText()
                if (isV1Meta(text)) {
                    return serializer.decodeFromString<ModuleMeta>(text)
                }
                // Legacy format: { id, source }
                val legacyInfo = serializer.decodeFromString<Module.Info>(text)
                return synthesizeV1Meta(modulePath, moduleId, legacyInfo.source, blobFile, metaFile)
            } catch (e: Exception) {
                log(TAG, WARN) { "recovery.meta_parse_failed: $modulePath, synthesizing from blob: ${e.message}" }
                // Fall through to synthesis from blob
            }
        }

        // module.json missing or unreadable, but payload.blob exists
        if (blobFile.exists()) {
            log(TAG, WARN) { "recovery.meta_missing: $modulePath, synthesizing from blob" }
            return synthesizeV1Meta(modulePath, moduleId, sourceDeviceId = null, blobFile, metaFile)
        }

        return null
    }

    private fun synthesizeV1Meta(
        modulePath: Path,
        moduleId: ModuleId,
        sourceDeviceId: java.util.UUID?,
        blobFile: Path,
        metaFile: Path,
    ): ModuleMeta {
        val blobSize = if (blobFile.exists()) blobFile.fileSize() else 0L
        val blobMtime = if (blobFile.exists()) blobFile.getLastModifiedTime().toInstant() else Instant.now()

        // Deterministic ETag: hash of (moduleId + file size + mtime) so repeated
        // synthesis without successful persist always produces the same ETag
        val etag = generateDeterministicEtag(moduleId, blobSize, blobMtime)

        // Use the device directory name as a fallback source device ID
        val effectiveSourceDeviceId = sourceDeviceId ?: run {
            try {
                java.util.UUID.fromString(modulePath.parent.parent.fileName.toString())
            } catch (e: Exception) {
                java.util.UUID(0, 0)
            }
        }

        val meta = ModuleMeta(
            schemaVersion = 1,
            moduleId = moduleId,
            sourceDeviceId = effectiveSourceDeviceId,
            etag = etag,
            modifiedAt = blobMtime,
            documentSizeBytes = blobSize,
            blobRefs = emptyList(),
        )

        // Best-effort persist
        try {
            val tempFile = modulePath.resolve("${META_FILENAME}.tmp")
            tempFile.writeText(serializer.encodeToString(meta))
            tempFile.moveTo(metaFile, overwrite = true)
            log(TAG) { "Migrated legacy module metadata: $modulePath" }
        } catch (e: Exception) {
            log(TAG, WARN) { "recovery.migration_persist_failed: $modulePath, using in-memory metadata: ${e.message}" }
        }

        // Best-effort persist access.json
        persistAccessMeta(modulePath, Instant.now())

        return meta
    }

    /**
     * Updates the access timestamp for a module. Called on blob reads/lists.
     * Coalesces disk writes via [recordAccess].
     */
    fun touchAccess(target: Device, moduleId: ModuleId) {
        val modulePath = target.getModulePath(moduleId)
        if (modulePath.exists()) {
            recordAccess(modulePath, Instant.now())
        }
    }

    /**
     * Records an access on a module. Always updates the in-memory shadow; persists to disk only
     * if the last disk write is older than [ACCESS_DEBOUNCE]. The GC loop flushes any pending
     * shadow writes so disk-divergence is bounded by one GC tick.
     */
    private fun recordAccess(modulePath: Path, now: Instant) {
        val prev = accessShadow[modulePath]
        if (prev == null || Duration.between(prev.lastPersistedAt, now) >= ACCESS_DEBOUNCE) {
            persistAccessMeta(modulePath, now)
        } else {
            accessShadow[modulePath] = prev.copy(lastAccessedAt = now)
        }
    }

    /**
     * Writes `access.json` immediately and refreshes the shadow so it tracks the disk state.
     * Used for write paths (legacy POST, PUT commit) where we want consistent on-disk metadata
     * alongside other module writes, and by [recordAccess] when the debounce has elapsed.
     */
    private fun persistAccessMeta(modulePath: Path, accessTime: Instant) {
        try {
            val accessFile = modulePath.resolve(ACCESS_FILENAME)
            val tempFile = modulePath.resolve("${ACCESS_FILENAME}.tmp")
            val accessMeta = AccessMeta(lastAccessedAt = accessTime)
            tempFile.writeText(serializer.encodeToString(accessMeta))
            tempFile.moveTo(accessFile, overwrite = true)
            accessShadow[modulePath] = AccessState(lastAccessedAt = accessTime, lastPersistedAt = accessTime)
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to persist $ACCESS_FILENAME for $modulePath: ${e.message}" }
        }
    }

    private fun persistMeta(modulePath: Path, meta: ModuleMeta) {
        val metaFile = modulePath.resolve(META_FILENAME)
        val tempFile = modulePath.resolve("${META_FILENAME}.tmp")
        tempFile.writeText(serializer.encodeToString(meta))
        tempFile.moveTo(metaFile, overwrite = true)
    }

    /**
     * Reads module payload into memory. Used by legacy clients and tests.
     */
    suspend fun read(caller: Device, target: Device, moduleId: ModuleId): Module.Read {
        val modulePath = target.getModulePath(moduleId)

        return try {
            target.sync.withLock {
                val meta = loadOrMigrateMeta(modulePath, moduleId)
                if (meta == null) {
                    Module.Read()
                } else {
                    val blobFile = modulePath.resolve(BLOB_FILENAME)
                    val payload = if (blobFile.exists()) blobFile.readBytes() else ByteArray(0)
                    recordAccess(modulePath, Instant.now())
                    Module.Read(
                        modifiedAt = meta.modifiedAt,
                        payload = payload,
                        etag = meta.etag,
                    )
                }
            }.also {
                log(TAG, VERBOSE) { "read(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId) -> ${it.size}B" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "read(${caller.id}, ${target.id}, $moduleId) failed: ${e.asLog()}" }
            throw e
        }
    }

    /**
     * Returns metadata and an already-open [InputStream] for streaming reads.
     * The stream is opened under the device sync lock so later concurrent writers
     * cannot replace the payload while the reader is still streaming — POSIX keeps
     * the open file descriptor pointing at the original inode even after rename/delete.
     * Caller must close [Module.ReadRef.blobStream].
     */
    suspend fun readRef(caller: Device, target: Device, moduleId: ModuleId): Module.ReadRef {
        val modulePath = target.getModulePath(moduleId)

        return try {
            target.sync.withLock {
                val meta = loadOrMigrateMeta(modulePath, moduleId)
                if (meta == null) {
                    Module.ReadRef()
                } else {
                    val blobFile = modulePath.resolve(BLOB_FILENAME)
                    val blobStream: InputStream? = if (blobFile.exists()) {
                        Files.newInputStream(blobFile)
                    } else {
                        null
                    }
                    recordAccess(modulePath, Instant.now())
                    Module.ReadRef(
                        modifiedAt = meta.modifiedAt,
                        blobStream = blobStream,
                        sizeBytes = meta.documentSizeBytes,
                        etag = meta.etag,
                    )
                }
            }.also {
                log(TAG, VERBOSE) { "readRef(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId) -> ${it.sizeBytes}B" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "readRef(${caller.id}, ${target.id}, $moduleId) failed: ${e.asLog()}" }
            throw e
        }
    }

    suspend fun write(caller: Device, target: Device, moduleId: ModuleId, write: Module.Write): ModuleMeta {
        val modulePath = target.getModulePath(moduleId)

        return try {
            target.sync.withLock {
                // Check for existing metadata — legacy POST is blocked if blobRefs exist
                val existingMeta = loadOrMigrateMeta(modulePath, moduleId)
                if (existingMeta != null && existingMeta.blobRefs.isNotEmpty()) {
                    throw BlobBackedModuleException(moduleId)
                }

                modulePath.apply {
                    if (!parent.exists()) parent.createDirectory()
                    if (!exists()) createDirectory()
                }

                // Write payload.blob first, then module.json as commit point
                val blobFile = modulePath.resolve(BLOB_FILENAME)
                val tempBlob = modulePath.resolve("${BLOB_FILENAME}.tmp")
                tempBlob.writeBytes(write.payload)
                tempBlob.moveTo(blobFile, overwrite = true)

                val now = Instant.now()
                val newEtag = generateRandomEtag()
                val meta = ModuleMeta(
                    schemaVersion = 1,
                    moduleId = moduleId,
                    sourceDeviceId = caller.id,
                    etag = newEtag,
                    modifiedAt = now,
                    documentSizeBytes = write.payload.size.toLong(),
                    blobRefs = emptyList(),
                )

                persistMeta(modulePath, meta)
                persistAccessMeta(modulePath, now)

                meta
            }.also {
                log(TAG, VERBOSE) { "write(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId) -> ${write.size}B" }
            }
        } catch (e: BlobBackedModuleException) {
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "write(${caller.id}, ${target.id}, $moduleId) failed: ${e.asLog()}" }
            throw e
        }
    }

    suspend fun delete(caller: Device, target: Device, moduleId: ModuleId) {
        log(TAG, VERBOSE) { "delete(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId)" }
        val modulePath = target.getModulePath(moduleId)

        target.sync.withLock {
            if (!modulePath.exists()) {
                log(TAG) { "delete(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId): didn't exist" }
                return
            }
            accessShadow.remove(modulePath)
            modulePath.deleteRecursively()
            log(TAG) { "delete(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId): deleted" }
        }
    }

    suspend fun clear(caller: Device, targets: Set<Device>) {
        log(TAG, VERBOSE) { "clear(${caller.id.shortId()}): Wiping ${targets.size} targets" }
        targets.forEach { target ->
            target.sync.withLock {
                if (target.modulesPath.exists()) {
                    accessShadow.keys.removeAll { it.startsWith(target.modulesPath) }
                    target.modulesPath.deleteRecursively()
                }
            }
        }
    }

    /**
     * Writes module payload without acquiring the device lock.
     * Caller must hold target.sync. Used by ModuleLifecycleService.
     */
    fun writeUnlocked(caller: Device, target: Device, moduleId: ModuleId, write: Module.Write): ModuleMeta {
        val modulePath = target.getModulePath(moduleId)
        modulePath.apply {
            if (!parent.exists()) parent.createDirectory()
            if (!exists()) createDirectory()
        }

        val blobFile = modulePath.resolve(BLOB_FILENAME)
        val tempBlob = modulePath.resolve("${BLOB_FILENAME}.tmp")
        tempBlob.writeBytes(write.payload)
        tempBlob.moveTo(blobFile, overwrite = true)

        val now = Instant.now()
        val newEtag = generateRandomEtag()
        val meta = ModuleMeta(
            schemaVersion = 1,
            moduleId = moduleId,
            sourceDeviceId = caller.id,
            etag = newEtag,
            modifiedAt = now,
            documentSizeBytes = write.payload.size.toLong(),
            blobRefs = emptyList(),
        )

        persistMeta(modulePath, meta)
        persistAccessMeta(modulePath, now)

        log(TAG, VERBOSE) { "writeUnlocked(${caller.id.shortId()}, ${target.id.shortId()}, $moduleId) -> ${write.size}B" }
        return meta
    }

    /**
     * Deletes module without acquiring the device lock.
     * Caller must hold target.sync. Used by ModuleLifecycleService.
     */
    fun deleteUnlocked(target: Device, moduleId: ModuleId) {
        val modulePath = target.getModulePath(moduleId)
        if (modulePath.exists()) {
            accessShadow.remove(modulePath)
            modulePath.deleteRecursively()
            log(TAG, VERBOSE) { "deleteUnlocked(${target.id.shortId()}, $moduleId): deleted" }
        }
    }

    /**
     * Clears all modules for a device without acquiring the device lock.
     * Caller must hold target.sync. Used by ModuleLifecycleService.
     */
    fun clearUnlocked(target: Device) {
        if (target.modulesPath.exists()) {
            accessShadow.keys.removeAll { it.startsWith(target.modulesPath) }
            target.modulesPath.deleteRecursively()
            log(TAG, VERBOSE) { "clearUnlocked(${target.id.shortId()}): wiped" }
        }
    }

    /**
     * Remove a single blobRef from module metadata. Caller must hold target.sync.
     * Validates If-Match against the current module etag. On success, returns the
     * removed ref plus the orphan blob directory path for async cleanup and the
     * new metadata. Sets sourceDeviceId = caller.id and advances etag/modifiedAt.
     *
     * If duplicate rows for the same blobId exist (post-recovery corruption),
     * remove exactly one and log a warning; the next DELETE reaps the duplicate.
     */
    fun removeBlobRefUnlocked(
        caller: Device,
        target: Device,
        moduleId: ModuleId,
        blobId: String,
        ifMatch: String,
    ): RemoveBlobRefResult {
        val modulePath = target.getModulePath(moduleId)
        val oldMeta = loadOrMigrateMeta(modulePath, moduleId)
            ?: return RemoveBlobRefResult.ModuleNotFound

        if (oldMeta.etag != ifMatch) {
            return RemoveBlobRefResult.EtagMismatch(currentEtag = oldMeta.etag)
        }

        val firstIndex = oldMeta.blobRefs.indexOfFirst { it.blobId == blobId }
        if (firstIndex < 0) return RemoveBlobRefResult.BlobNotFound

        val removed = oldMeta.blobRefs[firstIndex]
        val duplicateCount = oldMeta.blobRefs.count { it.blobId == blobId } - 1
        if (duplicateCount > 0) {
            log(TAG, WARN) { "removeBlobRefUnlocked: $moduleId has $duplicateCount duplicate blobRefs for $blobId; removing only first" }
        }

        val newRefs = oldMeta.blobRefs.toMutableList().also { it.removeAt(firstIndex) }
        val now = Instant.now()
        val newMeta = oldMeta.copy(
            sourceDeviceId = caller.id,
            etag = generateRandomEtag(),
            modifiedAt = now,
            blobRefs = newRefs,
        )
        persistMeta(modulePath, newMeta)
        persistAccessMeta(modulePath, now)

        val prefix = removed.storageKey.take(4)
        val orphanPath = modulePath.resolve("blobs").resolve(prefix).resolve(removed.storageKey)
        return RemoveBlobRefResult.Success(RemovedBlobRef(removed, orphanPath, newMeta))
    }

    /**
     * Loads metadata for an existing module. Returns null if the module doesn't exist.
     * Triggers lazy migration if metadata is legacy format.
     * Must be called under the device sync lock.
     */
    fun loadMeta(target: Device, moduleId: ModuleId): ModuleMeta? {
        val modulePath = target.getModulePath(moduleId)
        return loadOrMigrateMeta(modulePath, moduleId)
    }

    /**
     * Thread-safe metadata load — acquires device lock.
     */
    suspend fun loadMetaSafe(target: Device, moduleId: ModuleId): ModuleMeta? {
        return target.sync.withLock {
            loadMeta(target, moduleId)
        }
    }

    /**
     * Opens a committed live blob for streaming. Returns an already-open handle with
     * the declared size, or null if the blob isn't found.
     *
     * The handle is opened under the device sync lock so a concurrent commit that
     * orphan-deletes the backing file cannot corrupt the reader — POSIX keeps the
     * open descriptor pointing at the original inode even after unlink.
     *
     * Caller must close [BlobHandle.stream].
     */
    suspend fun openBlobHandle(target: Device, moduleId: ModuleId, blobId: String): BlobHandle? {
        return target.sync.withLock {
            val modulePath = target.getModulePath(moduleId)
            val meta = loadOrMigrateMeta(modulePath, moduleId) ?: return@withLock null
            val ref = meta.blobRefs.find { it.blobId == blobId } ?: return@withLock null
            val prefix = ref.storageKey.take(4)
            val blobFile = modulePath.resolve("blobs")
                .resolve(prefix)
                .resolve(ref.storageKey)
                .resolve(BLOB_FILENAME)
            if (!blobFile.exists()) return@withLock null
            BlobHandle(
                stream = Files.newInputStream(blobFile),
                sizeBytes = ref.sizeBytes,
                hashAlgorithm = ref.hashAlgorithm,
                hashHex = ref.hashHex,
            )
        }
    }

    /**
     * Returns the module path for a given device and module ID. Used by commit logic.
     */
    fun resolveModulePath(target: Device, moduleId: ModuleId): Path {
        return target.getModulePath(moduleId)
    }

    /**
     * Counts existing module directories for [target]. Includes session-only directories
     * because each one consumes a dirent and contributes to inode pressure regardless of
     * whether anything has committed there yet.
     */
    fun countModuleDirs(target: Device): Int {
        val modulesPath = target.modulesPath
        if (!modulesPath.exists()) return 0
        return modulesPath.listDirectoryEntries().size
    }

    /**
     * Whether `moduleId` already has on-disk state on [target] (committed or session-only).
     * Callers use this to skip the count cap when writing to an existing module.
     */
    fun moduleDirExists(target: Device, moduleId: ModuleId): Boolean {
        return target.getModulePath(moduleId).exists()
    }

    private val Device.modulesPath: Path
        get() = path.resolve(MODULES_DIR)

    companion object {
        private const val MODULES_DIR = "modules"
        internal const val META_FILENAME = "module.json"
        internal const val BLOB_FILENAME = "payload.blob"
        internal const val ACCESS_FILENAME = "access.json"
        private val ACCESS_DEBOUNCE: Duration = Duration.ofSeconds(30)
        private val TAG = logTag("Module", "Repo")

        /**
         * Distinguishes v1 metadata from legacy `{ id, source }` without decoding.
         * v1 always carries `schemaVersion`; legacy never does.
         */
        internal fun isV1Meta(text: String): Boolean = text.contains("\"schemaVersion\"")

        fun generateDeterministicEtag(moduleId: String, fileSize: Long, mtime: Instant): String {
            val input = "$moduleId:$fileSize:${mtime.toEpochMilli()}"
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            return hash.take(16).joinToString("") { "%02x".format(it) }
        }

        fun generateRandomEtag(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

class BlobBackedModuleException(val moduleId: ModuleId) :
    RuntimeException("Legacy write rejected: module $moduleId has external blob refs")

/**
 * Returned by [ModuleRepo.removeBlobRefUnlocked] on success; carries the removed ref,
 * the orphan blob directory path for async cleanup, and the new module metadata.
 */
data class RemovedBlobRef(
    val blobRef: BlobRef,
    val orphanPath: Path,
    val newMeta: ModuleMeta,
)

sealed interface RemoveBlobRefResult {
    data class Success(val removed: RemovedBlobRef) : RemoveBlobRefResult
    data object ModuleNotFound : RemoveBlobRefResult
    data object BlobNotFound : RemoveBlobRefResult
    data class EtagMismatch(val currentEtag: String) : RemoveBlobRefResult
}

/**
 * Result of [ModuleRepo.accountForModule]. Carries the byte total for quota accounting plus
 * the decoded v1 meta if present, so callers (recovery) can avoid a second read of module.json.
 */
internal data class ModuleAccounting(
    val bytes: Long,
    val v1Meta: ModuleMeta?,
)

/**
 * Already-open handle to a live blob. Caller owns [stream] and must close it.
 *
 * [hashAlgorithm] / [hashHex] mirror the values from the v1 [ModuleMeta.BlobRef] so the
 * download route can emit a strong ETag without a second metadata read.
 */
class BlobHandle(
    val stream: InputStream,
    val sizeBytes: Long,
    val hashAlgorithm: String? = null,
    val hashHex: String? = null,
) : AutoCloseable {
    override fun close() {
        try {
            stream.close()
        } catch (_: Exception) {
        }
    }
}
