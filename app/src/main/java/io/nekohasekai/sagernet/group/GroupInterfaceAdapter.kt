package io.nekohasekai.sagernet.group

import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(R.string.yes) { _, _ -> it.resume(true) }
                    .setNegativeButton(R.string.no) { _, _ -> it.resume(false) }
                    .setOnCancelListener { _ -> it.resume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        onMainDispatcher {
            if (changed == 0 && duplicate.isEmpty()) {
                if (byUser) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.group_no_difference, group.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@onMainDispatcher
            }

            val parts = mutableListOf<String>()
            if (added.isNotEmpty()) parts += context.getString(R.string.group_toast_added, added.size)
            if (updated.isNotEmpty()) parts += context.getString(R.string.group_toast_changed, updated.size)
            if (deleted.isNotEmpty()) parts += context.getString(R.string.group_toast_deleted, deleted.size)
            if (duplicate.isNotEmpty()) {
                parts += context.getString(R.string.group_toast_duplicate, duplicate.size)
            }
            val detail = parts.joinToString(" · ")
            Toast.makeText(
                context,
                context.getString(R.string.group_toast_updated, group.displayName(), changed, detail),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    override suspend fun alert(message: String) {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> it.resume(Unit) }
                    .setOnCancelListener { _ -> it.resume(Unit) }
                    .show()
            }
        }
    }

}
