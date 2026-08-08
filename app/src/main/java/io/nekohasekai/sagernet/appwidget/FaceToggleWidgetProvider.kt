package io.nekohasekai.sagernet.appwidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import io.nekohasekai.sagernet.R

class FaceToggleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // System periodic / restore path: try hourly random, then refresh UI.
        FaceToggleWidgetStore.maybeRandomizeHourly(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
        scheduleHourly(context)
    }

    override fun onEnabled(context: Context) {
        scheduleHourly(context)
    }

    override fun onDisabled(context: Context) {
        cancelHourly(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HOURLY,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                if (widgetIds(context).isNotEmpty()) {
                    if (intent.action == ACTION_HOURLY) {
                        FaceToggleWidgetStore.maybeRandomizeHourly(context, force = true)
                    }
                    updateAll(context)
                    scheduleHourly(context)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_HOURLY = "io.nekohasekai.sagernet.appwidget.ACTION_HOURLY"

        private fun pendingFlags(): Int {
            return PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        }

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.face_toggle_widget)
            views.setImageViewResource(R.id.face_image, FaceToggleWidgetStore.currentResId(context))
            val click = PendingIntent.getActivity(
                context,
                0,
                Intent(context, FaceToggleWidgetActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                pendingFlags(),
            )
            views.setOnClickPendingIntent(R.id.face_root, click)
            return views
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val views = buildViews(context)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        fun widgetIds(context: Context): IntArray {
            return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, FaceToggleWidgetProvider::class.java))
        }

        fun scheduleHourly(context: Context) {
            if (widgetIds(context).isEmpty()) return
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = hourlyPendingIntent(context)
            val trigger = SystemClock.elapsedRealtime() + FaceToggleWidgetStore.HOUR_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.ELAPSED_REALTIME, trigger, pi)
            }
        }

        fun cancelHourly(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(hourlyPendingIntent(context))
        }

        private fun hourlyPendingIntent(context: Context): PendingIntent {
            return PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, FaceToggleWidgetProvider::class.java).setAction(ACTION_HOURLY),
                pendingFlags(),
            )
        }
    }
}
