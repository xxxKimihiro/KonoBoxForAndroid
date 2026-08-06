package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.wifi.WifiManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.listByLineOrComma

/**
 * Trusted Wi‑Fi → keep VPN up but route all traffic Direct.
 * SSID whitelist is compared after normalizing Android's quoted / unknown forms.
 *
 * Uses WifiManager (not ConnectivityManager.activeNetwork) so detection still works
 * while the VPN is the default network.
 */
object WifiDirectHelper {

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

    fun currentSsid(): String? {
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

    fun whitelist(): List<String> {
        return DataStore.wifiDirectSsids.listByLineOrComma().mapNotNull { normalizeSsid(it) }
    }

    fun shouldUseDirect(): Boolean {
        if (!DataStore.wifiDirectEnabled) return false
        val ssid = currentSsid() ?: return false
        val list = whitelist()
        if (list.isEmpty()) return false
        return list.any { it == ssid }
    }
}
