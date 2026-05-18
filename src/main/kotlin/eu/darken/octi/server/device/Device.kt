package eu.darken.octi.server.device

import eu.darken.octi.server.account.AccountId
import eu.darken.octi.server.common.generateRandomKey
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Instant
import java.util.*


data class Device(
    val data: Data,
    val path: Path,
    val accountId: AccountId,
    val sync: Mutex = Mutex(),
) {
    fun isAuthorized(credentials: DeviceCredentials): Boolean {
        return accountId == credentials.accountId &&
            MessageDigest.isEqual(
                data.password.toByteArray(),
                credentials.devicePassword.toByteArray()
            )
    }

    val id: DeviceId
        get() = data.id

    val key: DeviceKey
        get() = DeviceKey(accountId, id)

    val password: String
        get() = data.password

    val version: String?
        get() = data.version

    val platform: String?
        get() = data.platform

    val label: String?
        get() = data.label

    val capabilities: Set<String>?
        get() = data.capabilities

    val addedAt: Instant
        get() = data.addedAt

    val lastSeen: Instant
        get() = data.lastSeen

    @Serializable
    data class Data(
        @Contextual val id: DeviceId,
        val password: String = generateRandomKey(),
        val version: String? = null,
        val platform: String? = null,
        val label: String? = null,
        /**
         * Feature capability tags published by the device, format `<namespace>:<value>`
         * (e.g. `encryption:AES256_GCM_SIV`). Opaque to the server — stored and echoed
         * as-is. `null` = device hasn't reported capabilities; an empty set = device
         * explicitly reports no capabilities. See [eu.darken.octi.server.common.parseCapabilitiesHeader].
         */
        val capabilities: Set<String>? = null,
        @Contextual val addedAt: Instant = Instant.now(),
        @Contextual val lastSeen: Instant = Instant.now(),
    ) {
        override fun toString(): String = "Device.Data(added=$addedAt, seen=$lastSeen, $id, ${password.take(8)}...)"
    }
}

typealias DeviceId = UUID
