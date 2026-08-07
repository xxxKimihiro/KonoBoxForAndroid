package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.listByLineOrComma

/**
 * Trusted Wi‑Fi → keep VPN up but route all traffic Direct.
 * SSID whitelist is compared after normalizing Android's quoted / unknown forms.
 *
 * Prefers WifiInfo from NetworkCapabilities.transportInfo (tied to the network
 * callback), then falls back to WifiManager so detection still works while the
 * VPN is the default network.
 */
object WifiDirectHelper {

    enum class DirectDecision {
        /** Current SSID is on the trusted whitelist → use Direct. */
        DIRECT,
        /** Feature off, empty whitelist, or SSID clearly not trusted → use proxy. */
        PROXY,
        /** SSID not readable yet / permission missing → do not change mode. */
        UNKNOWN,
    }

    fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var ssid = raw.trim()
        if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") {
            return null
        }
        return ssid
    }

    fun whitelist(): List<String> {
        return DataStore.wifiDirectSsids.listByLineOrComma().mapNotNull { normalizeSsid(it) }
    }

    fun currentSsid(network: Network? = SagerNet.underlyingNetwork): String? {
        ssidFromTransportInfo(network)?.let { return it }
        return ssidFromWifiManager()
    }

    /**
     * Decide Direct vs proxy. UNKNOWN means "keep the current mode" — used when
     * the network callback fires before WifiInfo is populated.
     */
    fun evaluate(network: Network? = SagerNet.underlyingNetwork): DirectDecision {
        if (!DataStore.wifiDirectEnabled) return DirectDecision.PROXY
        val list = whitelist()
        if (list.isEmpty()) return DirectDecision.PROXY
        val ssid = currentSsid(network) ?: return DirectDecision.UNKNOWN
        return if (list.any { it == ssid }) DirectDecision.DIRECT else DirectDecision.PROXY
    }

    /** True only when SSID is positively matched. UNKNOWN → false. */
    fun shouldUseDirect(network: Network? = SagerNet.underlyingNetwork): Boolean {
        return evaluate(network) == DirectDecision.DIRECT
    }

    private fun ssidFromTransportInfo(network: Network?): String? {
        if (network == null) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val caps = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val info = caps.transportInfo as? WifiInfo ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.networkId == -1) {
                return null
            }
            normalizeSsid(info.ssid)
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }

    private fun ssidFromWifiManager(): String? {
        return try {
            val wifiManager =
                SagerNet.application.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo ?: return null
            // networkId == -1 means not associated with an AP
            if (info.networkId == -1) return null
            normalizeSsid(info.ssid)
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }
}
