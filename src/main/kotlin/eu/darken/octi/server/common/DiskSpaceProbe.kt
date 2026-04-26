package eu.darken.octi.server.common

import eu.darken.octi.server.App
import eu.darken.octi.server.common.debug.logging.Logging.Priority.ERROR
import eu.darken.octi.server.common.debug.logging.asLog
import eu.darken.octi.server.common.debug.logging.log
import eu.darken.octi.server.common.debug.logging.logTag
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiskSpaceProbe @Inject constructor(
    private val config: App.Config,
) {

    internal data class Snapshot(val usable: Long, val atMs: Long)

    // Single volatile so readers always see a consistent (usable, atMs) pair — two
    // separate volatiles could pair a fresh timestamp with a stale value.
    @Volatile internal var snapshot: Snapshot = Snapshot(usable = -1L, atMs = 0L)

    internal fun usableBytes(): Long {
        val now = System.currentTimeMillis()
        val s = snapshot
        // -1 (probe failure) is cached for the TTL too, otherwise persistent FS errors
        // would trigger one syscall per request.
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
