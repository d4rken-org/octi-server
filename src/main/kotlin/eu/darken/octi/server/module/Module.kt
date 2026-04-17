package eu.darken.octi.server.module

import eu.darken.octi.server.device.DeviceId
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.time.Instant

interface Module {

    @Serializable
    data class Info(
        @Contextual @SerialName("id") val id: ModuleId,
        @Contextual @SerialName("source") val source: DeviceId,
    )

    data class Read(
        val modifiedAt: Instant? = null,
        val payload: ByteArray = ByteArray(0),
        val etag: String? = null,
    ) {
        val size: Int
            get() = payload.size
    }

    /**
     * Read result that provides an already-open [InputStream] for streaming.
     * The stream is opened while the device sync lock is held so concurrent writers
     * that rename/delete the underlying path cannot corrupt the response body.
     * The caller owns the handle and must [InputStream.close] it.
     */
    data class ReadRef(
        val modifiedAt: Instant? = null,
        val blobStream: InputStream? = null,
        val sizeBytes: Long = 0,
        val etag: String? = null,
    )

    data class Write(
        val payload: ByteArray,
    ) {
        val size: Int
            get() = payload.size
    }
}

typealias ModuleId = String
