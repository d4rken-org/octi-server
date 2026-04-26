package eu.darken.octi.server.common

import eu.darken.octi.server.App
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteRecursively

class DiskSpaceProbeTest {

    private lateinit var dataPath: Path

    @BeforeEach
    fun setUp() {
        dataPath = Path.of("./build/tmp/disk-probe-test/${UUID.randomUUID()}")
        Files.createDirectories(dataPath)
    }

    @AfterEach
    fun tearDown() {
        runCatching { dataPath.deleteRecursively() }
    }

    private fun probe(
        floorBytes: Long,
        path: Path = dataPath,
    ): DiskSpaceProbe = DiskSpaceProbe(
        App.Config(
            port = 0,
            dataPath = path,
            minFreeDiskSpaceBytes = floorBytes,
        ),
    )

    @Test
    fun `floor of zero disables the gate`() {
        val p = probe(floorBytes = 0L)
        p.hasHeadroom(0L) shouldBe true
        p.hasHeadroom(Long.MAX_VALUE) shouldBe true
    }

    @Test
    fun `negative floor also disables the gate`() {
        val p = probe(floorBytes = -1L)
        p.hasHeadroom(1024L) shouldBe true
    }

    @Test
    fun `Long MAX floor always blocks`() {
        val p = probe(floorBytes = Long.MAX_VALUE)
        p.hasHeadroom(0L) shouldBe false
        p.hasHeadroom(1L) shouldBe false
    }

    @Test
    fun `positive floor with realistic free space passes for small writes`() {
        // 1 KB floor on a healthy CI/dev disk is trivially satisfied.
        val p = probe(floorBytes = 1024L)
        p.hasHeadroom(1024L) shouldBe true
    }

    @Test
    fun `probe failure on non-existent path fails closed`() {
        val missing = Path.of("./build/tmp/disk-probe-test/does-not-exist-${UUID.randomUUID()}")
        // Floor must be positive so the early bypass doesn't short-circuit the probe.
        val p = probe(floorBytes = 1L, path = missing)
        p.hasHeadroom(0L) shouldBe false
    }

    @Test
    fun `usableBytes is cached within the TTL`() {
        val p = probe(floorBytes = 1L)
        val first = p.usableBytes()
        first shouldBeGreaterThanOrEqual 0L
        val firstStamp = p.snapshot.atMs

        // A second call within the same millisecond window must hit the cache —
        // the snapshot should not be replaced, and the returned value should equal `first`.
        val second = p.usableBytes()
        second shouldBe first
        p.snapshot.atMs shouldBe firstStamp
        p.snapshot.usable shouldBe first
    }

    @Test
    fun `usableBytes refreshes after the TTL expires`() {
        val p = probe(floorBytes = 1L)
        p.usableBytes()
        val firstStamp = p.snapshot.atMs

        Thread.sleep(DiskSpaceProbe.CACHE_TTL_MS + 50L)

        p.usableBytes()
        p.snapshot.atMs shouldBeGreaterThan firstStamp
    }
}
