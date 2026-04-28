package eu.darken.octi.server.regression

import eu.darken.octi.server.App
import eu.darken.octi.server.module.BlobFixtures
import java.util.UUID
import kotlin.random.Random

/**
 * Generates a deterministic synthetic data tree on disk before [App.launch], shaped like a
 * production deployment but with no real data. Used by [CrossVersionFlowTest] and the
 * regression-synthetic-replay CI job to exercise StartupRecoveryService at scale without
 * exposing real user data.
 *
 * Reuses the existing [BlobFixtures] helpers so the on-disk JSON layout matches what production
 * actually writes. UUIDs are seeded for reproducibility — the same accountCount produces the
 * same tree every time.
 */
internal object SyntheticDataFixture {

    /**
     * Seeds [config.dataPath] with [accountCount] accounts. Each account has 1–3 devices,
     * each device has 5–10 modules with small random byte payloads.
     *
     * Returns a few sentinel IDs so a caller can assert reachability via the API.
     */
    fun seed(config: App.Config, accountCount: Int = 500, seed: Long = 42L): Sentinel {
        val rng = Random(seed)
        var firstAccount: UUID? = null
        var firstDevice: UUID? = null
        var firstModule: String? = null

        repeat(accountCount) { accountIdx ->
            val accountId = UUID(rng.nextLong(), rng.nextLong())
            val deviceCount = rng.nextInt(1, 4) // 1..3
            repeat(deviceCount) { deviceIdx ->
                val deviceId = UUID(rng.nextLong(), rng.nextLong())
                if (accountIdx == 0 && deviceIdx == 0) {
                    firstAccount = accountId
                    firstDevice = deviceId
                }
                BlobFixtures.seedAccountDevice(config.dataPath, accountId, deviceId)

                val moduleCount = rng.nextInt(5, 11) // 5..10
                repeat(moduleCount) { modIdx ->
                    val moduleId = "synthetic.module.${accountIdx}.${deviceIdx}.${modIdx}"
                    if (firstModule == null) firstModule = moduleId
                    val moduleDir = BlobFixtures.moduleDir(config.dataPath, accountId, deviceId, moduleId)
                    BlobFixtures.writeModuleMeta(
                        moduleDir,
                        BlobFixtures.moduleMeta(moduleId, deviceId, blobRefs = emptyList()),
                    )
                    val payloadSize = rng.nextInt(64, 1024) // small, encryption-shaped
                    val payload = ByteArray(payloadSize).also { rng.nextBytes(it) }
                    BlobFixtures.writeModulePayloadBlob(moduleDir, payload)
                }
            }
        }

        return Sentinel(
            firstAccount = requireNotNull(firstAccount) { "accountCount must be > 0" },
            firstDevice = requireNotNull(firstDevice),
            firstModule = requireNotNull(firstModule),
        )
    }

    data class Sentinel(
        val firstAccount: UUID,
        val firstDevice: UUID,
        val firstModule: String,
    )

    /**
     * CLI entrypoint: `gradle generateSyntheticData --args "/output/dir [accountCount]"`.
     * Used by the regression-synthetic-replay CI job to populate a docker-mountable data dir
     * before booting the freshly built server image.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "usage: SyntheticDataFixture <output-dir> [accountCount]" }
        val outputDir = java.io.File(args[0]).also { it.mkdirs() }
        val accountCount = args.getOrNull(1)?.toIntOrNull() ?: 500
        val cfg = eu.darken.octi.server.App.Config(
            dataPath = outputDir.toPath(),
            port = 0,
            isDebug = false,
            rateLimit = null,
            minFreeDiskSpaceBytes = 0L,
        )
        val sentinel = seed(cfg, accountCount = accountCount)
        // Print sentinel to stdout so the calling shell can pipe it to a sanity-check curl.
        println("first-account=${sentinel.firstAccount}")
        println("first-device=${sentinel.firstDevice}")
        println("first-module=${sentinel.firstModule}")
        println("account-count=$accountCount")
    }
}
