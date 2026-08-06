package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.OrderFallbackGroup
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libcore.Libcore

/**
 * Ordered fallback: probe members (via connectionTestURL through each outbound) and select the
 * first working one. Latency mode uses sing-box urltest and needs no watcher.
 */
class AutoOutboundWatcher(
    private val scope: CoroutineScope,
    private val groups: List<OrderFallbackGroup>,
) {
    private var job: Job? = null

    fun start(service: BaseService.Interface) {
        stop()
        if (groups.isEmpty()) return
        job = scope.launch(Dispatchers.IO) {
            delay(5_000L)
            while (isActive) {
                try {
                    checkOnce(service)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (e: Exception) {
                    Logs.w(e)
                }
                delay(30_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun checkOnce(service: BaseService.Interface) {
        val box = service.data.proxy?.box ?: return
        if (DataStore.wifiDirectActive) return
        val url = DataStore.connectionTestURL
        for (group in groups) {
            if (group.memberTags.isEmpty()) continue
            var selected: String? = null
            for (tag in group.memberTags) {
                try {
                    Libcore.urlTestOutbound(box, tag, url, 3000)
                    selected = tag
                    break
                } catch (e: Exception) {
                    Logs.d("auto-outbound probe fail ${group.groupTag}/$tag: ${e.message}")
                }
            }
            if (selected != null) {
                val ok = box.selectGroupOutbound(group.groupTag, selected)
                if (ok) {
                    Logs.d("auto-outbound selected ${group.groupTag} -> $selected")
                }
            }
        }
    }
}
