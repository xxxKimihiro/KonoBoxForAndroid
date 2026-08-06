package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.AutoOutboundMode
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libcore.Libcore

/**
 * After connect: if the current node fails the connection test, refresh the subscription
 * (when applicable) and switch to the first working node in the same group.
 */
class ConnectFailover(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start(service: BaseService.Interface) {
        stop()
        if (!DataStore.autoSwitchOnFail) return
        job = scope.launch(Dispatchers.IO) {
            delay(2_000L)
            if (!isActive || DataStore.wifiDirectActive) return@launch
            try {
                ensureWorkingNode(service)
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun ensureWorkingNode(service: BaseService.Interface) {
        val proxy = service.data.proxy ?: return
        val box = proxy.box ?: return
        val url = DataStore.connectionTestURL

        try {
            Libcore.urlTest(box, url, 3000)
            return
        } catch (e: Exception) {
            Logs.w("auto-switch: current node failed: ${e.message}")
        }

        val profile = proxy.profile
        val group = SagerDatabase.groupDao.getById(profile.groupId) ?: return

        if (group.subscription != null) {
            try {
                Logs.d("auto-switch: refreshing subscription ${group.displayName()}")
                GroupUpdater.executeUpdate(group, false)
            } catch (e: Exception) {
                Logs.w("auto-switch: subscription refresh failed: ${e.message}")
            }
            delay(1_000L)
            if (!isActive || DataStore.wifiDirectActive) return
            val boxAfterRefresh = service.data.proxy?.box
            if (boxAfterRefresh != null) {
                try {
                    Libcore.urlTest(boxAfterRefresh, url, 3000)
                    return
                } catch (_: Exception) {
                }
            }
        }

        // Latency urltest already picks working members; avoid fighting it with selector select.
        if (DataStore.autoOutboundMode == AutoOutboundMode.LATENCY) {
            Logs.d("auto-switch: latency urltest mode, skip manual switch")
            return
        }

        val members = SagerDatabase.proxyDao.getByGroup(group.id)
            .filter {
                it.type != ProxyEntity.TYPE_AUTO_GROUP && it.type != ProxyEntity.TYPE_CHAIN
            }
            .sortedBy { it.userOrder }
        if (members.isEmpty()) return

        val running = service.data.proxy ?: return
        val tagMap = running.config.profileTagMap
        val boxNow = running.box ?: return

        // Fast path: probe outbounds already present in the running selector / group.
        val tagged = members.mapNotNull { ent ->
            val tag = tagMap[ent.id] ?: return@mapNotNull null
            if (tag == TAG_PROXY) return@mapNotNull null
            ent to tag
        }
        if (tagged.isNotEmpty()) {
            val winner = probeFirstWorking(tagged, boxNow, url) ?: run {
                Logs.w("auto-switch: no working node in group ${group.displayName()}")
                return
            }
            val (ent, tag) = winner
            val ok = boxNow.selectGroupOutbound(TAG_PROXY, tag) || boxNow.selectOutbound(tag)
            if (!ok) {
                Logs.w("auto-switch: select failed for ${ent.displayName()} ($tag)")
                return
            }
            applySelection(service, ent)
            Logs.d("auto-switch: switched to ${ent.displayName()}")
            return
        }

        // Slow path: no multi-outbound config — test candidates out-of-process, then reload.
        for (ent in members) {
            if (!isActive) return
            if (ent.id == profile.id) continue
            try {
                UrlTest().doTest(ent)
                DataStore.selectedProxy = ent.id
                Logs.d("auto-switch: forceReload to ${ent.displayName()}")
                service.forceReload()
                return
            } catch (e: Exception) {
                Logs.d("auto-switch: probe ${ent.displayName()} fail: ${e.message}")
            }
        }
        Logs.w("auto-switch: no working node in group ${group.displayName()}")
    }

    private suspend fun probeFirstWorking(
        candidates: List<Pair<ProxyEntity, String>>,
        box: libcore.BoxInstance,
        url: String,
    ): Pair<ProxyEntity, String>? {
        val concurrency = DataStore.connectionTestConcurrent.coerceIn(1, 8)
        for (batch in candidates.chunked(concurrency)) {
            if (!scope.isActive) return null
            val winner = coroutineScope {
                batch.map { (ent, tag) ->
                    async(Dispatchers.IO) {
                        try {
                            Libcore.urlTestOutbound(box, tag, url, 3000)
                            ent to tag
                        } catch (e: Exception) {
                            Logs.d("auto-switch: probe ${ent.displayName()} fail: ${e.message}")
                            null
                        }
                    }
                }.awaitAll().firstOrNull { it != null }
            }
            if (winner != null) return winner
        }
        return null
    }

    private suspend fun applySelection(service: BaseService.Interface, ent: ProxyEntity) {
        DataStore.selectedProxy = ent.id
        Libcore.resetAllConnections(true)
        service.data.proxy?.apply {
            looper?.selectMain(ent.id)
            displayProfileName = ServiceNotification.genTitle(ent)
            service.data.notification?.postNotificationTitle(displayProfileName)
        }
        service.data.binder.broadcast { b ->
            b.cbSelectorUpdate(ent.id)
        }
    }
}
