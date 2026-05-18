package eu.darken.octi.server.device

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class DevicesResponse(
    @SerialName("devices") val devices: List<Device>,
) {
    @Serializable
    data class Device(
        @Contextual @SerialName("id") val id: DeviceId,
        @SerialName("version") val version: String?,
        @SerialName("platform") val platform: String?,
        @SerialName("label") val label: String?,
        @SerialName("capabilities") val capabilities: Set<String>? = null,
        @Contextual @SerialName("addedAt") val addedAt: Instant,
        @Contextual @SerialName("lastSeen") val lastSeen: Instant,
    )
}
