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
                    // Load module metadata and accumulate used bytes
                    val metaFile = moduleDir.resolve("module.json")
                    var moduleMeta: ModuleMeta? = null
                    if (metaFile.exists()) {
                        try {
                            val text = metaFile.readText()
                            if (ModuleRepo.isV1Meta(text)) {
                                moduleMeta = json.decodeFromString<ModuleMeta>(text)
                                usedBytes += moduleMeta.documentSizeBytes
                                for (ref in moduleMeta.blobRefs) {
                                    usedBytes += ref.sizeBytes
                                }
                            } else {
                                // Legacy metadata — count payload.blob size
                                val blobFile = moduleDir.resolve("payload.blob")
                                if (blobFile.exists()) {
                                    usedBytes += blobFile.fileSize()
                                }
                            }
                        } catch (e: Exception) {
                            log(TAG, WARN) { "recovery: failed to read module meta at $metaFile: ${e.message}" }
                            val blobFile = moduleDir.resolve("payload.blob")
                            if (blobFile.exists()) {
                                usedBytes += blobFile.fileSize()
                            }
                        }
                    } else {
                        val blobFile = moduleDir.resolve("payload.blob")
                        if (blobFile.exists()) {
                            usedBytes += blobFile.fileSize()
                        }
                    }

                    // Scan blobs/ for orphans
                    val blobsDir = moduleDir.resolve("blobs")
                    if (blobsDir.exists() && moduleMeta != null) {
                        val liveStorageKeys = moduleMeta.blobRefs.map { it.storageKey }.toSet()
                        blobsDir.listDirectoryEntries().forEach { prefixDir ->
                            prefixDir.listDirectoryEntries().forEach { storageKeyDir ->
                                val key = storageKeyDir.fileName.toString()
                                if (key !in liveStorageKeys) {
                                    log(TAG) { "recovery.orphan_blob_reclaimed: $storageKeyDir" }
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
                        }

                        // ACTIVE state but only payload.blob exists (finalize rename succeeded, state persist didn't)
                        if (sessionMeta.state == UploadSessionMeta.State.ACTIVE) {
                            val partFile = sessionDir.resolve(UploadSessionRepo.PART_FILENAME)
                            val blobFile = sessionDir.resolve(UploadSessionRepo.BLOB_FILENAME)
                            if (!partFile.exists() && blobFile.exists()) {
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
                        } else {
                            sessionRepo.loadSession(sessionMeta, sessionDir)
                            reservedBytes += sessionMeta.expectedSizeBytes
                        }
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

    private data class AccountBytes(val used: Long, val reserved: Long)

    companion object {
        private val TAG = logTag("Startup", "Recovery")
    }
}
