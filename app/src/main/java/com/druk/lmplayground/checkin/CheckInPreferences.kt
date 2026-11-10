package com.druk.lmplayground.checkin

import android.content.Context

/**
 * How often [CheckInScheduler] enqueues `proactive-checkin`, and the daily
 * notification cap that goes with that cadence.
 */
enum class CheckInFrequency {
    SIX_HOURS,
    TWELVE_HOURS,
    DAILY,
    WEEKLY,
    ;

    fun intervalHours(): Long = when (this) {
        SIX_HOURS -> 6L
        TWELVE_HOURS -> 12L
        DAILY -> 24L
        WEEKLY -> 24L * 7L
    }

    fun dailyCap(): Int = when (this) {
        SIX_HOURS -> 4
        TWELVE_HOURS -> 2
        DAILY -> 1
        WEEKLY -> 1
    }

    companion object {
        fun fromStorage(raw: String?): CheckInFrequency =
            entries.firstOrNull { it.name == raw } ?: DAILY
    }
}

/**
 * Check-in settings. [enabled] defaults to false: the feature is opt-in.
 *
 * [hybridCloudEnabled] is set only after the user has already completed a
 * cloud turn. Extraction never turns it on by itself, and never calls the
 * cloud when it is false.
 */
class CheckInPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var frequency: CheckInFrequency
        get() = CheckInFrequency.fromStorage(prefs.getString(KEY_FREQUENCY, null))
        set(value) {
            prefs.edit().putString(KEY_FREQUENCY, value.name).apply()
        }

    var inactivityThresholdMs: Long
        get() = prefs.getLong(KEY_INACTIVITY, DEFAULT_INACTIVITY_MS)
        set(value) {
            prefs.edit().putLong(KEY_INACTIVITY, value).apply()
        }

    var quietStartMinute: Int
        get() = prefs.getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_START, value.coerceIn(0, MINUTES_PER_DAY - 1)).apply()
        }

    var quietEndMinute: Int
        get() = prefs.getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
        set(value) {
            prefs.edit().putInt(KEY_QUIET_END, value.coerceIn(0, MINUTES_PER_DAY - 1)).apply()
        }

    var hybridCloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_HYBRID, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HYBRID, value).apply()
        }

    fun notificationsSent(day: String): Int {
        if (prefs.getString(KEY_DAY, null) != day) return 0
        return prefs.getInt(KEY_COUNT, 0)
    }

    fun recordNotification(day: String) {
        val count = notificationsSent(day) + 1
        prefs.edit().putString(KEY_DAY, day).putInt(KEY_COUNT, count).apply()
    }

    companion object {
        const val PREFS = "chidori_check_ins"
        const val KEY_ENABLED = "enabled"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_INACTIVITY = "inactivity_ms"
        const val KEY_QUIET_START = "quiet_start_minute"
        const val KEY_QUIET_END = "quiet_end_minute"
        const val KEY_HYBRID = "hybrid_cloud_enabled"
        const val KEY_DAY = "notify_day"
        const val KEY_COUNT = "notify_count"
        const val KEY_PENDING = "pending_extract"

        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_INACTIVITY_MS = 2L * 60L * 60L * 1000L
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 8 * 60

        val INACTIVITY_OPTIONS_MS = listOf(
            30L * 60L * 1000L,
            60L * 60L * 1000L,
            DEFAULT_INACTIVITY_MS,
            6L * 60L * 60L * 1000L,
        )
    }
}
