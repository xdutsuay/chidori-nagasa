package com.druk.lmplayground.checkin

import android.content.Context

/**
 * App-wide pause for background work (KMA-333).
 *
 * Check-ins and [com.druk.lmplayground.coordinator.notifications.NeedsYouBadgePoller]
 * both read [isPaused]. Future routine schedulers must read this same switch
 * before they do work. The flag is stored apart from the check-in toggle so
 * a routine can honour it without depending on check-ins being enabled.
 *
 * Default is false: background activity is allowed until the user pauses it.
 * The switch lives on Settings → Check-ins.
 */
class BackgroundActivityPause(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isPaused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PAUSED, value).apply()
        }

    companion object {
        const val PREFS = "chidori_background"
        const val KEY_PAUSED = "background_activity_paused"
    }
}
