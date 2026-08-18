package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.listByLineOrComma
import java.util.concurrent.ConcurrentHashMap

/**
 * Trusted Wi‑Fi → keep VPN up but route all traffic Direct.
 * SSID whitelist is compared after normalizing Android's quoted / unknown forms.
 *
 * Physical Wi‑Fi / cellular is tracked by a dedicated [NetworkCallback] that
 * excludes the VPN tun. Do not use [SagerNet.underlyingNetwork] as the source
 * of truth: on many APIs that callback reports the VPN itself, so Wi‑Fi ↔
 * mobile switches never fire.
 */
object WifiDirectHelper {

    enum class DirectDecision {
        /** Current SSID is on the trusted whitelist → use Direct. */
        DIRECT,
        /** Feature off, empty whitelist, or not on a trusted Wi‑Fi → use proxy. */
        PROXY,
        /** On Wi‑Fi but SSID not readable yet → do not change mode. */
        UNKNOWN,
    }

    private val watched = ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile
    private var watchReady = false
    private var watchCallback: ConnectivityManager.NetworkCallback? = null
    private var watchListener: (() -> Unit)? = null

    fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var ssid = raw.trim()
        if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) {
            ssid = ssid.substring(1, ssid.lastIndex)
        }
        if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") {
            return null
        }
        return ssid
    }

    fun whitelist(): List<String> {
        return DataStore.wifiDirectSsids.listByLineOrComma().mapNotNull { normalizeSsid(it) }
    }

    fun currentSsid(): String? {
        ssidFromWatchedWifi()?.let { return it }
        return ssidFromWifiManager()
    }

    /**
     * Decide Direct vs proxy.
     *
     * Order of evidence:
     * 1. Wi‑Fi adapter off → PROXY (user switched to mobile by toggling Wi‑Fi).
     * 2. Watch has observed networks and none are Wi‑Fi → PROXY.
     * 3. A Wi‑Fi network is present → match SSID, or UNKNOWN if SSID is not ready.
     * 4. Watch not ready yet → fall back to WifiManager association.
     */
    fun evaluate(): DirectDecision {
        if (!DataStore.wifiDirectEnabled) return DirectDecision.PROXY
        val list = whitelist()
        if (list.isEmpty()) return DirectDecision.PROXY
        if (!isWifiAdapterEnabled()) return DirectDecision.PROXY

        val watching = watchCallback != null
        if (watching && watchReady && !hasWatchedWifi()) {
            return DirectDecision.PROXY
        }

        val ssid = currentSsid()
        if (ssid != null) {
            return if (list.any { it == ssid }) DirectDecision.DIRECT else DirectDecision.PROXY
        }
        return if (hasWatchedWifi() || isAssociatedWifi()) {
            DirectDecision.UNKNOWN
        } else {
            DirectDecision.PROXY
        }
    }

    /** True only when SSID is positively matched. UNKNOWN → false. */
    fun shouldUseDirect(): Boolean {
        return evaluate() == DirectDecision.DIRECT
    }

    fun isWifiAdapterEnabled(): Boolean {
        return try {
            val wifiManager =
                SagerNet.application.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val state = wifiManager.wifiState
            state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
        } catch (e: Exception) {
            Logs.w(e)
            true
        }
    }

    fun startWatch(listener: () -> Unit) {
        stopWatch()
        watchListener = listener
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SagerNet.connectivity.getNetworkCapabilities(network)?.let {
                    watched[network] = it
                }
                markReadyAndNotify()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                watched[network] = networkCapabilities
                markReadyAndNotify()
            }

            override fun onLost(network: Network) {
                watched.remove(network)
                markReadyAndNotify()
            }
        }
        watchCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SagerNet.connectivity.registerNetworkCallback(
                    request, callback, Handler(Looper.getMainLooper())
                )
            } else {
                SagerNet.connectivity.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            Logs.w(e)
            watchCallback = null
            watchListener = null
        }
    }

    fun stopWatch() {
        watchCallback?.let { callback ->
            try {
                SagerNet.connectivity.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        watchCallback = null
        watchListener = null
        watchReady = false
        watched.clear()
    }

    private fun markReadyAndNotify() {
        watchReady = true
        watchListener?.invoke()
    }

    private fun hasWatchedWifi(): Boolean {
        return watched.values.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    }

    private fun ssidFromWatchedWifi(): String? {
        for (caps in watched.values) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            ssidFromCapabilities(caps)?.let { return it }
        }
        return null
    }

    private fun ssidFromCapabilities(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.networkId == -1) {
            return null
        }
        return normalizeSsid(info.ssid)
    }

    private fun isAssociatedWifi(): Boolean {
        if (hasWatchedWifi()) return true
        return try {
            val wifiManager =
                SagerNet.application.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            info != null && info.networkId != -1
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
    }

    private fun ssidFromWifiManager(): String? {
        return try {
            val wifiManager =
                SagerNet.application.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo ?: return null
            if (info.networkId == -1) return null
            normalizeSsid(info.ssid)
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }
}
