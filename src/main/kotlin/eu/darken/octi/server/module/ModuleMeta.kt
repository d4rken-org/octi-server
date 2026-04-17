package eu.darken.octi.server.module

import eu.darken.octi.server.device.DeviceId
import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ModuleMeta(
    // @EncodeDefault forces `schemaVersion` into the JSON even though it has a default value,
    // so the on-disk file can be distinguished from legacy `{ id, source }` by presence of this field.
    @EncodeDefault val schemaVersion: Int = 1,
    val moduleId: String,
    @Contextual val sourceDeviceId: DeviceId,
    val etag: String,
    @Contextual val modifiedAt: Instant,
    val documentSizeBytes: Long,
    val blobRefs: List<BlobRef> = emptyList(),
)

@Serializable
data class BlobRef(
    val blobId: String,
    val storageKey: String,
    val sizeBytes: Long,
    val hashAlgorithm: String? = null,
    val hashHex: String? = null,
)

@Serializable
data class AccessMeta(
    @Contextual val lastAccessedAt: Instant,
)
