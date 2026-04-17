package eu.darken.octi.server.module

import java.security.MessageDigest

/**
 * Returns the on-disk directory name for this module.
 *
 * Currently SHA-1 hex of the UTF-8 bytes. Do not change without a migration —
 * existing `modules/{dirname}/` directories on deployed servers depend on this
 * being stable.
 */
@OptIn(ExperimentalStdlibApi::class)
internal fun ModuleId.toModuleDirName(): String =
    MessageDigest.getInstance("SHA-1").digest(toByteArray()).toHexString()
