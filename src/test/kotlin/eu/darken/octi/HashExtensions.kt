package eu.darken.octi

import java.security.MessageDigest

@OptIn(ExperimentalStdlibApi::class)
internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHexString()
