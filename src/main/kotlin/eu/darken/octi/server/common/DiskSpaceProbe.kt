package eu.darken.octi.server.common

import eu.darken.octi.server.App
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `usableSpace` for the data path's filesystem with a short in-memory cache so a
 * burst of blob requests doesn't repeat the syscall. Used by [BlobRoute] to short-circuit
 * blob uploads when the host disk is approaching exhaustion — a per-account quota cannot
 * protect against the sum of all account quotas exceeding actual host capacity.
 *
 * Best-effort, not a strict reservation. See plan: `we-need-a-type-binary-rose.md`.
 */
@Singleton
class DiskSpaceProbe @Inject constructor(
    private val config: App.Config,
) {

    internal data class Snapshot(val usable: Long, val atMs: Long)

    // Single volatile reference so readers see (usable, atMs) atomically — separate
    // volatile fields can interleave and pair a fresh timestamp with a stale value.
    @Volatile internal var snapshot: Snapshot = Snapshot(usable = -1L, atMs = 0L)

    internal fun usableBytes(): Long {
        val now = System.currentTimeMillis()
        val s = snapshot
        // Cache failures (-1) for the same TTL — repeated syscalls during a persistent
        // FS error would otherwise hammer the OS at request rate. hasHeadroom treats
        // -1 as fail-closed.
        if (now - s.atMs < CACHE_TTL_MS) return s.usable
        val result = try {
            Files.getFileStore(config.dataPath).usableSpace
        } catch (e: Exception) {
            log(TAG, ERROR) { "usableSpace probe failed: ${e.asLog()}" }
            -1L
        }
        snapshot = Snapshot(usable = result, atMs = now)
        return result
    }

    /**
     * @param incomingBytes upper bound on bytes the caller is about to write.
     * Returns true if [incomingBytes] can be accepted while leaving at least
     * [App.Config.minFreeDiskSpaceBytes] free. A non-positive floor disables the gate;
     * a probe failure fails closed.
     */
    fun hasHeadroom(incomingBytes: Long): Boolean {
        if (config.minFreeDiskSpaceBytes <= 0L) return true
        val usable = usableBytes()
        if (usable < 0L) return false
        return usable - incomingBytes >= config.minFreeDiskSpaceBytes
    }

    companion object {
        internal const val CACHE_TTL_MS = 1_000L
        private val TAG = logTag("DiskSpaceProbe")
    }
}
