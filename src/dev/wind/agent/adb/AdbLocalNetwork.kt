package dev.wind.agent.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal data class AdbLocalRoute(val handle: Long = 0, val name: String = "DEFAULT_LOOPBACK", val vpnDetected: Boolean = false)
internal data class AdbRouteCandidate(val handle: Long, val kind: String, val notVpn: Boolean)

/** Selects only socket routing, never a remote host or a process-wide network. */
internal object AdbLocalNetwork {
    fun select(vpn: Boolean, candidates: List<AdbRouteCandidate>): AdbLocalRoute {
        if (!vpn) return AdbLocalRoute()
        val order = listOf("WIFI", "ETHERNET", "CELLULAR")
        val physical = candidates.filter { it.notVpn && it.handle > 0 && it.kind in order }
            .minByOrNull { order.indexOf(it.kind) }
        return if (physical != null) AdbLocalRoute(physical.handle, "LOOPBACK_THEN_${physical.kind}", true)
            else AdbLocalRoute(0, "VPN_DEFAULT_LOOPBACK", true)
    }

    fun snapshot(context: Context): AdbLocalRoute = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) AdbLocalRoute() else {
            val networks = manager.allNetworks.mapNotNull { network ->
                manager.getNetworkCapabilities(network)?.let { network to it }
            }
            val vpn = networks.any { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } ||
                manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            select(vpn, networks.map { (network, caps) ->
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    else -> "OTHER"
                }
                AdbRouteCandidate(network.networkHandle, kind, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            })
        }
    } catch (_: Exception) { AdbLocalRoute(0, "NETWORK_INSPECTION_UNAVAILABLE") }
}
