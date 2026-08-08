package io.nekohasekai.sagernet.appwidget

import android.content.Context
import io.nekohasekai.sagernet.R
import kotlin.random.Random

object FaceToggleWidgetStore {
    private const val PREF = "face_toggle_widget"
    private const val KEY_FACE_INDEX = "face_index"
    private const val KEY_LAST_HOURLY = "last_hourly_ms"

    val FACE_ICONS = intArrayOf(
        R.drawable.ic_notif_face_1,
        R.drawable.ic_notif_face_2,
        R.drawable.ic_notif_face_3,
        R.drawable.ic_notif_face_4,
        R.drawable.ic_notif_face_5,
        R.drawable.ic_notif_face_6,
        R.drawable.ic_notif_face_7,
        R.drawable.ic_notif_face_8,
        R.drawable.ic_notif_face_9,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun currentIndex(context: Context): Int {
        val idx = prefs(context).getInt(KEY_FACE_INDEX, 0)
        return idx.coerceIn(0, FACE_ICONS.lastIndex)
    }

    fun currentResId(context: Context): Int = FACE_ICONS[currentIndex(context)]

    /** Click: advance to the next face (wrap around). */
    fun advanceOnClick(context: Context): Int {
        val next = (currentIndex(context) + 1) % FACE_ICONS.size
        prefs(context).edit().putInt(KEY_FACE_INDEX, next).apply()
        return FACE_ICONS[next]
    }

    /** Hourly: pick a different random face when due. Returns true if changed. */
    fun maybeRandomizeHourly(context: Context, force: Boolean = false): Boolean {
        val sp = prefs(context)
        val now = System.currentTimeMillis()
        val last = sp.getLong(KEY_LAST_HOURLY, 0L)
        if (!force && last > 0L && now - last < HOUR_MS) return false

        var idx = Random.nextInt(FACE_ICONS.size)
        val current = currentIndex(context)
        if (FACE_ICONS.size > 1 && idx == current) {
            idx = (idx + 1) % FACE_ICONS.size
        }
        sp.edit()
            .putInt(KEY_FACE_INDEX, idx)
            .putLong(KEY_LAST_HOURLY, now)
            .apply()
        return true
    }

    const val HOUR_MS = 60 * 60 * 1000L
}
