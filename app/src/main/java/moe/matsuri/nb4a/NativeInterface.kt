package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    /**
     * Enumerate interfaces via java.net.NetworkInterface (works without netlink).
     * Flags match Go net.Flags bit values so Tailscale netmon can see HaveV4/HaveV6.
     */
    override fun localInterfaces(): String {
        val arr = JSONArray()
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            null
        } ?: return "[]"
        for (iface in ifaces) {
            try {
                var flags = 0
                // net.FlagUp=1, Broadcast=2, Loopback=4, PointToPoint=8, Multicast=16, Running=32
                if (iface.isUp) flags = flags or 1 or 32
                if (iface.supportsMulticast()) flags = flags or 16
                if (iface.isLoopback) flags = flags or 4
                if (iface.isPointToPoint) flags = flags or 8
                if (!iface.isLoopback && !iface.isPointToPoint) flags = flags or 2
                val addrs = JSONArray()
                for (ifa in iface.interfaceAddresses) {
                    val host = when (val a = ifa.address) {
                        is Inet4Address -> a.hostAddress
                        is Inet6Address -> a.hostAddress?.substringBefore('%')
                        else -> a.hostAddress
                    } ?: continue
                    addrs.put("$host/${ifa.networkPrefixLength}")
                }
                val hw = try {
                    iface.hardwareAddress?.joinToString(":") { b ->
                        String.format("%02x", b)
                    } ?: ""
                } catch (_: Exception) {
                    ""
                }
                arr.put(
                    JSONObject()
                        .put("name", iface.name)
                        .put("index", iface.index)
                        .put("mtu", try {
                            iface.mtu
                        } catch (_: Exception) {
                            0
                        })
                        .put("flags", flags)
                        .put("hardware_addr", hw)
                        .put("addresses", addrs)
                )
            } catch (_: Exception) {
                // skip unreadable interface
            }
        }
        return arr.toString()
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
