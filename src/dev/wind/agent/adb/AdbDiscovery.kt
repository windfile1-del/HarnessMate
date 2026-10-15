package dev.wind.agent.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayDeque

/** One bounded mDNS search. Only this device's interface addresses may yield a port.
 * All public methods and callbacks run on the main thread; no host escapes this class.
 */
class AdbDiscovery(context: Context) {
    enum class Kind(val serviceType: String) {
        PAIRING("_adb-tls-pairing._tcp."), CONNECT("_adb-tls-connect._tcp.")
    }

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolver: NsdManager.ResolveListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val seen = HashSet<String>()
    private var deadline: Runnable? = null
    private var result: ((Int) -> Unit)? = null
    private var stopped: (() -> Unit)? = null

    fun start(kind: Kind, onPort: (Int) -> Unit, onStopped: () -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        stop()
        if (Build.VERSION.SDK_INT < 30 || nsd == null) { onStopped(); return }
        val epoch = generation
        result = onPort
        stopped = onStopped
        fun active() = epoch == generation && discovery != null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) { handler.post { if (active()) finish() } }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { handler.post { if (active()) finish() } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { handler.post { if (active()) finish() } }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { handler.post {
                if (active()) {
                    pending.removeAll { it.serviceName == serviceInfo.serviceName }
                    seen.remove(serviceInfo.serviceName)
                }
            } }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { handler.post {
                if (active() && serviceInfo.serviceType.trimEnd('.') == kind.serviceType.trimEnd('.') &&
                    pending.size < 16 && seen.size < 64 && seen.add(serviceInfo.serviceName)) {
                    pending.add(serviceInfo)
                    resolveNext(epoch)
                }
            } }
        }
        discovery = listener
        deadline = Runnable { if (active()) finish() }.also { handler.postDelayed(it, SEARCH_TIMEOUT_MS) }
        try {
            // Older NSD implementations need multicast reception explicitly enabled.
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicast = wifi?.createMulticastLock("vega-adb-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            nsd.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) { finish() }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(epoch: Int) {
        if (epoch != generation || discovery == null || resolver != null || pending.isEmpty()) return
        val info = pending.removeFirst()
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { handler.post {
                if (epoch == generation) { resolver = null; resolveNext(epoch) }
            } }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) { handler.post {
                if (epoch != generation || discovery == null) return@post
                resolver = null
                val port = serviceInfo.port
                if (port in 1..65535 && isLocalInterface(serviceInfo.host)) {
                    val callback = result
                    stop()
                    callback?.invoke(port)
                } else resolveNext(epoch)
            } }
        }
        resolver = listener
        try { nsd?.resolveService(info, listener) }
        catch (_: Exception) { resolver = null; resolveNext(epoch) }
    }

    private fun isLocalInterface(address: InetAddress?): Boolean {
        if (address == null || address.isAnyLocalAddress || address.isMulticastAddress) return false
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            interfaces.toList().any { it.isUp && it.inetAddresses.toList().any { local ->
                local.address.contentEquals(address.address)
            } }
        } catch (_: Exception) { false }
    }

    private fun finish() {
        val callback = stopped
        stop()
        callback?.invoke()
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        generation++ // Reject callbacks queued before cancellation, including old resolves on API 30-33.
        deadline?.let { handler.removeCallbacks(it) }
        deadline = null
        val oldDiscovery = discovery
        val oldResolver = resolver
        discovery = null
        resolver = null
        result = null
        stopped = null
        pending.clear()
        seen.clear()
        if (oldDiscovery != null) runCatching { nsd?.stopServiceDiscovery(oldDiscovery) }
        if (Build.VERSION.SDK_INT >= 34 && oldResolver != null) runCatching { nsd?.stopServiceResolution(oldResolver) }
        multicast?.let { runCatching { if (it.isHeld) it.release() } }
        multicast = null
    }

    companion object { const val SEARCH_TIMEOUT_MS = 45_000L }
}
