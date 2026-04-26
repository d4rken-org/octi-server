package eu.darken.octi.server.common

import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

val TrustedProxyIpsKey = AttributeKey<Set<String>>("TrustedProxyIps")

fun ApplicationRequest.clientIp(trustedProxyIps: Set<String> = IpHelper.DEFAULT_TRUSTED_PROXY_IPS): String {
    val connectionIp = IpHelper.normalize(local.remoteAddress)
    if (!IpHelper.isTrustedProxy(connectionIp, trustedProxyIps)) return connectionIp

    val forwardedIp = headers["X-Real-IP"]?.trim()
        ?.let { IpHelper.parseIpOrNull(it) }
        ?: headers["X-Forwarded-For"]?.let { IpHelper.resolveForwardedFor(it, trustedProxyIps) }

    return forwardedIp ?: connectionIp
}

object IpHelper {

    val DEFAULT_TRUSTED_PROXY_IPS = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    fun isLoopback(ip: String): Boolean = normalize(ip) in DEFAULT_TRUSTED_PROXY_IPS

    fun isTrustedProxy(ip: String, trustedProxyIps: Set<String>): Boolean {
        return normalize(ip) in trustedProxyIps.mapTo(mutableSetOf()) { normalize(it) }
    }

    fun isValid(ip: String): Boolean = parseIpOrNull(ip) != null

    fun parseIpOrNull(raw: String): String? {
        val normalized = normalize(raw)
        return when {
            IPV4_REGEX.matches(normalized) -> {
                val octets = normalized.split(".").map { it.toIntOrNull() ?: return null }
                if (octets.all { it in 0..255 }) normalized else null
            }
            normalized.contains(':') -> parseIpv6OrNull(normalized)
            else -> null
        }
    }

    fun resolveForwardedFor(header: String, trustedProxyIps: Set<String>): String? {
        val chain = header.split(",").mapNotNull { parseIpOrNull(it) }
        if (chain.isEmpty()) return null
        return chain.asReversed().firstOrNull { !isTrustedProxy(it, trustedProxyIps) } ?: chain.first()
    }

    fun normalize(raw: String): String {
        var ip = raw.trim().trimStart('/')
        if (ip.startsWith("[") && ip.contains("]")) {
            ip = ip.substringAfter('[').substringBefore(']')
        }
        if (ip.count { it == ':' } == 1 && ip.substringAfter(':').all { it.isDigit() }) {
            ip = ip.substringBefore(':')
        }
        return ip
    }

    private fun parseIpv6OrNull(ip: String): String? {
        if (ip.contains('%')) return null
        return try {
            val parsed = InetAddress.getByName(ip)
            when (parsed) {
                is Inet6Address -> parsed.hostAddress
                is Inet4Address -> null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private val IPV4_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
}
