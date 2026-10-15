package dev.wind.agent.artifact

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Stricter destination rules for bytes that may later be installed. */
internal object ArtifactNetworkPolicy {
    fun parseUrl(raw: String): URI {
        if (raw.length !in 1..4096 || raw.any { it <= ' ' || it == '\u007f' }) {
            throw ArtifactDownloadException("DOWNLOAD_URL_INVALID")
        }
        val uri = try { URI(raw) } catch (_: Exception) {
            throw ArtifactDownloadException("DOWNLOAD_URL_INVALID")
        }
        val host = uri.host?.lowercase(Locale.US) ?: throw ArtifactDownloadException("DOWNLOAD_HOST_INVALID")
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.userInfo != null ||
            uri.fragment != null || uri.port !in listOf(-1, 443) ||
            host.length > 253 || !host.contains('.') || host.endsWith('.') ||
            host.startsWith('.') || host.contains("..") || host.startsWith('[') ||
            host.split('.').any { it.length !in 1..63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) } ||
            isIpv4Literal(host) || host == "metadata.google.internal" ||
            host.endsWith(".local") || host.endsWith(".localhost") ||
            host.endsWith(".internal") || host.endsWith(".lan") ||
            host.endsWith(".home") || host.endsWith(".intranet")
        ) throw ArtifactDownloadException("DOWNLOAD_DESTINATION_BLOCKED")
        val target = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        if (target.any { it.code !in 0x21..0x7e }) throw ArtifactDownloadException("DOWNLOAD_URL_INVALID")
        return uri
    }

    /** Only public unicast addresses are eligible for a socket connection. */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            val c = bytes[2].toInt() and 255
            return a !in listOf(0, 10, 127) && a < 224 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                !(a == 172 && b in 16..31) && !(a == 192 && (b == 168 || b == 0 && c in listOf(0, 2))) &&
                !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
                !(a == 203 && b == 0 && c == 113)
        }
        if (address is Inet6Address && bytes.size == 16) {
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            // Restrict the first slice to ordinary IPv6 global unicast.
            if (first !in 0x20..0x3f) return false
            // Documentation and special-purpose 2001::/23 addresses.
            if (first == 0x20 && second == 0x01 &&
                ((bytes[2].toInt() and 255) < 0x02 ||
                    (bytes[2].toInt() and 255) == 0x0d && (bytes[3].toInt() and 255) == 0xb8)
            ) return false
            return true
        }
        return false
    }

    private fun isIpv4Literal(host: String): Boolean =
        host.all { it in '0'..'9' || it == '.' }
}
