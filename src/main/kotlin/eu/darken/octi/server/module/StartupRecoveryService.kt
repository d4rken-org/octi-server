package eu.darken.octi.server.module

import eu.darken.octi.server.App
import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.account.AccountStorageTracker
import eu.darken.octi.server.common.debug.logging.Logging.Priority.*
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import eu.darken.octi.server.device.DeviceRepo
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.*

@Singleton
class StartupRecoveryService @Inject constructor(
    private val config: App.Config,
    private val storageTracker: AccountStorageTracker,
    private val sessionRepo: UploadSessionRepo,
    private val moduleRepo: ModuleRepo,
    private val deviceRepo: DeviceRepo,
    private val json: Json,
) {

    /**
     * Runs synchronous startup recovery. Must be called from App.launch() before
     * Server.start() and before any GC coroutines start.
     */
    fun recover() {
        log(TAG, INFO) { "Starting recovery scan..." }
        val accountsDir = config.dataPath.resolve("accounts")
        if (!accountsDir.exists()) {
            log(TAG, INFO) { "No accounts directory found, nothing to recover" }
            return
        }

        val accountUsage = mutableMapOf<AccountId, AccountBytes>()

        accountsDir.listDirectoryEntries().forEach { accountDir ->
            val accountId = try {
                UUID.fromString(accountDir.fileName.toString())
            } catch (e: Exception) {
                return@forEach
            }

            val devicesDir = accountDir.resolve("devices")
            if (!devicesDir.exists()) return@forEach

            var usedBytes = 0L
            var reservedBytes = 0L

            devicesDir.listDirectoryEntries().forEach devices@{ deviceDir ->
                val modulesDir = deviceDir.resolve("modules")
                if (!modulesDir.exists()) return@devices

                val deviceId = try {
                    UUID.fromString(deviceDir.fileName.toString())
                } catch (e: Exception) {
                    return@devices
                }

                modulesDir.listDirectoryEntries().forEach modules@{ moduleDir ->
                    try {
                    // Single source of truth for legacy/non-v1 "bytes accounted for"; v1 blob
                    // refs are counted only after their live payloads have been validated.
                    val accounting = moduleRepo.accountForModule(moduleDir)
                    var moduleMeta: ModuleMeta? = accounting.v1Meta
                    if (moduleMeta == null) {
                        usedBytes += accounting.bytes
                    } else {
                        var validatedMeta = validateLiveBlobRefs(moduleDir, moduleMeta)
                        // Auto-heal documentSizeBytes when on-disk payload.blob has drifted
                        // from the metadata-recorded size (crash mid-document-write before
                        // module.json rename). Plan §"Startup Recovery" item 4.
                        val docFile = moduleDir.resolve("payload.blob")
                        val actualDocSize = if (docFile.exists()) docFile.fileSize() else 0L
                        if (actualDocSize != validatedMeta.documentSizeBytes) {
                            log(TAG) {
                                "recovery.document_size_normalized: ${moduleDir.fileName} ${validatedMeta.documentSizeBytes} -> $actualDocSize"
                            }
                            validatedMeta = validatedMeta.copy(
                                documentSizeBytes = actualDocSize,
                                etag = ModuleRepo.generateRandomEtag(),
                                modifiedAt = Instant.now(),
                            )
                        }
                        // Persist once if either blobRefs or documentSizeBytes changed.
                        if (validatedMeta != moduleMeta) {
                            persistModuleMeta(moduleDir, validatedMeta)
                        }
                        moduleMeta = validatedMeta
                        usedBytes += validatedMeta.documentSizeBytes + validatedMeta.blobRefs.sumOf { it.sizeBytes }
                    }

                    // Scan blobs/ for orphans
                    val blobsDir = moduleDir.resolve("blobs")
                    if (blobsDir.exists()) {
                        val liveStorageKeys = moduleMeta?.blobRefs?.map { it.storageKey }?.toSet().orEmpty()
                        blobsDir.listDirectoryEntries().forEach { prefixDir ->
                            prefixDir.listDirectoryEntries().forEach { storageKeyDir ->
                                val key = storageKeyDir.fileName.toString()
                                if (key !in liveStorageKeys) {
                                    log(TAG) { "recovery.orphan_reclaimed: $storageKeyDir" }
                                    try {
                                        storageKeyDir.deleteRecursively()
                                    } catch (e: Exception) {
                                        log(TAG, WARN) { "recovery: failed to delete orphan blob $storageKeyDir: ${e.message}" }
                                    }
                                }
                            }
                        }
                    }

                    // Scan sessions/
                    val sessionsDir = moduleDir.resolve("sessions")
                    if (!sessionsDir.exists()) return@modules

                    sessionsDir.listDirectoryEntries().forEach sessions@{ sessionDir ->
                        try {
                        val sessionMetaFile = sessionDir.resolve(UploadSessionRepo.SESSION_META_FILENAME)
                        if (!sessionMetaFile.exists()) {
                            log(TAG) { "recovery.session_malformed: no session.json in $sessionDir" }
                            sessionDir.deleteRecursively()
                            return@sessions
                        }

                        val sessionMeta = try {
                            json.decodeFromString<UploadSessionMeta>(sessionMetaFile.readText())
                        } catch (e: Exception) {
                            log(TAG) { "recovery.session_malformed: $sessionDir: ${e.message}" }
                            sessionDir.deleteRecursively()
                            return@sessions
                        }

                        if (!isSessionScopeValid(accountId, deviceId, moduleDir, sessionDir, sessionMeta)) {
                            log(TAG) { "recovery.session_invalid_scope: $sessionDir" }
                            sessionDir.deleteRecursively()
                            return@sessions
                        }

                        // Expired
                        if (sessionMeta.isExpired()) {
                            log(TAG) { "recovery: deleting expired session ${sessionMeta.sessionId}" }
                            sessionDir.deleteRecursively()
                            return@sessions
                        }

                        // COMPLETE session whose blobId is already live
                        if (sessionMeta.state == UploadSessionMeta.State.COMPLETE && moduleMeta != null) {
                            val isAlreadyLive = moduleMeta.blobRefs.any { it.blobId == sessionMeta.blobId }
                            if (isAlreadyLive) {
                                log(TAG) { "recovery.session_stale_committed: ${sessionMeta.sessionId}" }
                                sessionDir.deleteRecursively()
                                return@sessions
                            }
                        }

                        // COMPLETE session with no payload.blob
                        if (sessionMeta.state == UploadSessionMeta.State.COMPLETE) {
                            val blobFile = sessionDir.resolve(UploadSessionRepo.BLOB_FILENAME)
                            if (!blobFile.exists()) {
                                log(TAG) { "recovery.session_orphaned: COMPLETE but no blob: ${sessionMeta.sessionId}" }
                                sessionDir.deleteRecursively()
                                return@sessions
                            }
                            if (blobFile.fileSize() != sessionMeta.expectedSizeBytes) {
                                log(TAG) { "recovery.session_invalid_size: COMPLETE ${sessionMeta.sessionId}" }
                                sessionDir.deleteRecursively()
                                return@sessions
                            }
                            if (!hasValidCompleteHash(sessionMeta, blobFile)) {
                                log(TAG) { "recovery.session_invalid_hash: COMPLETE ${sessionMeta.sessionId}" }
                                sessionDir.deleteRecursively()
                                return@sessions
                            }
                        }

                        // ACTIVE state but only payload.blob exists (finalize rename succeeded, state persist didn't)
                        if (sessionMeta.state == UploadSessionMeta.State.ACTIVE) {
                            val partFile = sessionDir.resolve(UploadSessionRepo.PART_FILENAME)
                            val blobFile = sessionDir.resolve(UploadSessionRepo.BLOB_FILENAME)
                            if (!partFile.exists() && blobFile.exists()) {
                                if (blobFile.fileSize() != sessionMeta.expectedSizeBytes) {
                                    log(TAG) { "recovery.session_invalid_size: promoted ${sessionMeta.sessionId}" }
                                    sessionDir.deleteRecursively()
                                    return@sessions
                                }
                                if (!hasValidCompleteHash(sessionMeta, blobFile)) {
                                    log(TAG) { "recovery.session_invalid_hash: promoted ${sessionMeta.sessionId}" }
                                    sessionDir.deleteRecursively()
                                    return@sessions
                                }
                                log(TAG) { "recovery.session_state_promoted: ${sessionMeta.sessionId}" }
                                val promoted = sessionMeta.copy(
                                    state = UploadSessionMeta.State.COMPLETE,
                                    offsetBytes = sessionMeta.expectedSizeBytes,
                                )
                                persistSessionMeta(sessionDir, promoted)
                                sessionRepo.loadSession(promoted, sessionDir)
                                reservedBytes += promoted.expectedSizeBytes
                                return@sessions
                            }
                        }

                        // Normalize offset mismatches
                        val partFile = sessionDir.resolve(UploadSessionRepo.PART_FILENAME)
                        if (partFile.exists() && sessionMeta.state == UploadSessionMeta.State.ACTIVE) {
                            val actualSize = partFile.fileSize()
                            if (actualSize > sessionMeta.expectedSizeBytes) {
                                log(TAG) { "recovery.session_invalid_size: ACTIVE ${sessionMeta.sessionId}" }
                                sessionDir.deleteRecursively()
                                return@sessions
                            }
                            val normalized = if (actualSize < sessionMeta.offsetBytes) {
                                log(TAG) { "recovery.session_offset_truncated: ${sessionMeta.sessionId} offset ${sessionMeta.offsetBytes} -> $actualSize" }
                                sessionMeta.copy(offsetBytes = actualSize)
                            } else if (actualSize > sessionMeta.offsetBytes) {
                                log(TAG) { "recovery.session_offset_advanced: ${sessionMeta.sessionId} offset ${sessionMeta.offsetBytes} -> $actualSize" }
                                sessionMeta.copy(offsetBytes = actualSize)
                            } else {
                                sessionMeta
                            }
                            if (normalized !== sessionMeta) {
                                persistSessionMeta(sessionDir, normalized)
                            }
                            sessionRepo.loadSession(normalized, sessionDir)
                            reservedBytes += normalized.expectedSizeBytes
                        } else if (sessionMeta.state == UploadSessionMeta.State.COMPLETE) {
                            sessionRepo.loadSession(sessionMeta, sessionDir)
                            reservedBytes += sessionMeta.expectedSizeBytes
                        } else if (sessionMeta.state == UploadSessionMeta.State.ACTIVE && sessionMeta.expectedSizeBytes == 0L) {
                            sessionRepo.loadSession(sessionMeta, sessionDir)
                            reservedBytes += sessionMeta.expectedSizeBytes
                        } else {
                            log(TAG) { "recovery.session_malformed: missing staged payload for ${sessionMeta.sessionId}" }
                            sessionDir.deleteRecursively()
                        }
                        } catch (e: Exception) {
                            log(TAG, WARN) { "recovery.session_failed: $sessionDir: ${e.message}" }
                            return@sessions
                        }
                    }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "recovery.module_failed: $moduleDir: ${e.message}" }
                        return@modules
                    }
                }
            }

            accountUsage[accountId] = AccountBytes(usedBytes, reservedBytes)
        }

        // Rebuild quota for all accounts
        for ((accountId, bytes) in accountUsage) {
            storageTracker.rebuildUsage(accountId, bytes.used, bytes.reserved)
        }

        log(TAG, INFO) { "Recovery complete: ${accountUsage.size} accounts scanned" }
    }

    private fun persistSessionMeta(sessionDir: Path, meta: UploadSessionMeta) {
        val metaFile = sessionDir.resolve(UploadSessionRepo.SESSION_META_FILENAME)
        val tempFile = sessionDir.resolve("${UploadSessionRepo.SESSION_META_FILENAME}.tmp")
        tempFile.writeText(json.encodeToString(meta))
        tempFile.moveTo(metaFile, overwrite = true)
    }

    private fun persistModuleMeta(moduleDir: Path, meta: ModuleMeta) {
        val metaFile = moduleDir.resolve(ModuleRepo.META_FILENAME)
        val tempFile = moduleDir.resolve("${ModuleRepo.META_FILENAME}.tmp")
        tempFile.writeText(json.encodeToString(meta))
        tempFile.moveTo(metaFile, overwrite = true)
    }

    private fun validateLiveBlobRefs(moduleDir: Path, meta: ModuleMeta): ModuleMeta {
        val validRefs = meta.blobRefs.filter { ref ->
            val blobFile = liveBlobFile(moduleDir, ref.storageKey)
            val valid = blobFile.exists()
                && blobFile.fileSize() == ref.sizeBytes
                && hasValidLiveBlobHash(ref, blobFile)
            if (!valid) {
                log(TAG) { "recovery.blobref_removed: ${ref.blobId} (storageKey=${ref.storageKey}) — file invalid: ${blobFile.parent}" }
                try {
                    blobFile.parent?.deleteRecursively()
                } catch (e: Exception) {
                    log(TAG, WARN) { "recovery: failed to delete invalid blob ${blobFile.parent}: ${e.message}" }
                }
            }
            valid
        }
        if (validRefs.size == meta.blobRefs.size) return meta
        return meta.copy(
            etag = ModuleRepo.generateRandomEtag(),
            modifiedAt = Instant.now(),
            blobRefs = validRefs,
        )
    }

    private fun liveBlobFile(moduleDir: Path, storageKey: String): Path {
        return moduleDir.resolve("blobs")
            .resolve(storageKey.take(4))
            .resolve(storageKey)
            .resolve(UploadSessionRepo.BLOB_FILENAME)
    }

    private fun isSessionScopeValid(
        accountId: AccountId,
        deviceId: UUID,
        moduleDir: Path,
        sessionDir: Path,
        meta: UploadSessionMeta,
    ): Boolean {
        if (meta.accountId != accountId) return false
        if (meta.deviceId != deviceId) return false
        if (sessionDir.fileName.toString() != meta.sessionId) return false
        if (moduleDir.fileName.toString() != meta.moduleId.toModuleDirName()) return false
        if (meta.expectedSizeBytes < 0 || meta.expectedSizeBytes > config.maxBlobBytes) return false
        if (meta.offsetBytes < 0 || meta.offsetBytes > meta.expectedSizeBytes) return false
        if (meta.hashAlgorithm != null && !meta.hashAlgorithm.equals("sha256", ignoreCase = true) && !meta.hashAlgorithm.equals("SHA-256", ignoreCase = true)) {
            return false
        }
        if (meta.hashHex != null && !SHA256_HEX_REGEX.matches(meta.hashHex)) return false
        val expectedSessionDir = config.dataPath
            .resolve("accounts").resolve(meta.accountId.toString())
            .resolve("devices").resolve(meta.deviceId.toString())
            .resolve("modules").resolve(meta.moduleId.toModuleDirName())
            .resolve("sessions").resolve(meta.sessionId)
            .normalize()
        return sessionDir.normalize() == expectedSessionDir
    }

    private fun hasValidCompleteHash(meta: UploadSessionMeta, file: Path): Boolean {
        val hashHex = meta.hashHex ?: return false
        val algorithm = meta.hashAlgorithm ?: return false
        if (!algorithm.equals("sha256", ignoreCase = true) && !algorithm.equals("SHA-256", ignoreCase = true)) return false
        if (!SHA256_HEX_REGEX.matches(hashHex)) return false
        return computeSha256(file) == hashHex
    }

    private fun hasValidLiveBlobHash(ref: BlobRef, file: Path): Boolean {
        val hashHex = ref.hashHex ?: return true
        val algorithm = ref.hashAlgorithm ?: return false
        if (!algorithm.equals("sha256", ignoreCase = true) && !algorithm.equals("SHA-256", ignoreCase = true)) return false
        if (!SHA256_HEX_REGEX.matches(hashHex)) return false
        return computeSha256(file) == hashHex
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

    private data class AccountBytes(val used: Long, val reserved: Long)

    companion object {
        private val SHA256_HEX_REGEX = "^[0-9a-f]{64}$".toRegex()
        private val TAG = logTag("Startup", "Recovery")
    }
}
