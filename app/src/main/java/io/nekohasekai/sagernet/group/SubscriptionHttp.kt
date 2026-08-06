package io.nekohasekai.sagernet.group

import android.net.Network
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.readableMessage
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetch subscription HTTP content. When the VPN is up and the selected node is dead,
 * a plain Libcore request via SOCKS still goes through that node and can hang the update.
 * With [DataStore.subscriptionUpdateDirectFallback] (default on), failures retry on the
 * system underlying network so the fetch bypasses the VPN tunnel.
 */
object SubscriptionHttp {

    data class Result(
        val content: String,
        val userinfo: String?,
        val contentDisposition: String?,
    )

    fun fetch(link: String, userAgent: String?): Result {
        val ua = userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT
        return try {
            fetchViaLibcore(link, ua, useSocks = true)
        } catch (e: Exception) {
            if (!DataStore.subscriptionUpdateDirectFallback) throw e
            Logs.w("Subscription via proxy failed, retry direct: ${e.readableMessage}")

            val network = SagerNet.underlyingNetwork
            if (network != null) {
                try {
                    return fetchViaUnderlyingNetwork(network, link, ua)
                } catch (e2: Exception) {
                    Logs.w("Subscription via underlying network failed: ${e2.readableMessage}")
                }
            }

            // Best-effort: Libcore without SOCKS (may still hit VPN routing)
            fetchViaLibcore(link, ua, useSocks = false)
        }
    }

    private fun fetchViaLibcore(link: String, userAgent: String, useSocks: Boolean): Result {
        val response = Libcore.newHttpClient().apply {
            if (useSocks) trySocks5(DataStore.mixedPort)
            tryH3Direct()
            when (DataStore.appTLSVersion) {
                "1.3" -> restrictedTLS()
            }
        }.newRequest().apply {
            if (DataStore.allowInsecureOnRequest) {
                allowInsecure()
            }
            setURL(link)
            setUserAgent(userAgent)
        }.execute()

        val content = Util.getStringBox(response.contentString)
        return Result(
            content = content,
            userinfo = Util.getStringBox(response.getHeader("Subscription-Userinfo"))
                .takeIf { it.isNotBlank() },
            contentDisposition = Util.getStringBox(response.getHeader("content-disposition"))
                .takeIf { it.isNotBlank() },
        )
    }

    private fun fetchViaUnderlyingNetwork(
        network: Network,
        link: String,
        userAgent: String,
    ): Result {
        val conn = network.openConnection(URL(link)) as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            val content = conn.inputStream.bufferedReader().use { it.readText() }
            return Result(
                content = content,
                userinfo = conn.getHeaderField("Subscription-Userinfo"),
                contentDisposition = conn.getHeaderField("content-disposition"),
            )
        } finally {
            conn.disconnect()
        }
    }
}
