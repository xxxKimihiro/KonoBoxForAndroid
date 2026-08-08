package io.nekohasekai.sagernet.appwidget

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.ui.VpnRequestActivity

/**
 * Transparent activity launched by the face widget.
 * - Always switches the widget face
 * - Starts the proxy if stopped; force-reloads (restart) if already running
 */
class FaceToggleWidgetActivity : ComponentActivity(), SagerConnection.Callback {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_WIDGET)
    private var handled = false

    private val startService = registerForActivityResult(VpnRequestActivity.StartService()) {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FaceToggleWidgetStore.advanceOnClick(this)
        FaceToggleWidgetProvider.updateAll(this)
        connection.connect(this, this)
    }

    override fun onServiceConnected(service: ISagerNetService) {
        if (handled) return
        handled = true
        val state = BaseService.State.values()[service.state]
        when {
            state.canStop -> {
                SagerNet.forceReloadService()
                finish()
            }
            state == BaseService.State.Stopped -> {
                startService.launch(null)
            }
            else -> finish()
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceDisconnected() {
        if (!handled) finish()
    }

    override fun onBinderDied() {
        if (!handled) finish()
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }
}
