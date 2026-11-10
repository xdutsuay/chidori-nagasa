package com.druk.lmplayground.checkin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Check-ins are opt-in. Pause defaults to off, which means background work is allowed. */
@RunWith(RobolectricTestRunner::class)
class CheckInPreferencesTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(CheckInPreferences.PREFS, 0).edit().clear().commit()
        app.getSharedPreferences(BackgroundActivityPause.PREFS, 0).edit().clear().commit()
    }

    @Test
    fun defaultsAreOff() {
        val prefs = CheckInPreferences(app)
        assertFalse(prefs.enabled)
        assertFalse(prefs.hybridCloudEnabled)
        assertEquals(CheckInFrequency.DAILY, prefs.frequency)
        assertEquals(CheckInPreferences.DEFAULT_INACTIVITY_MS, prefs.inactivityThresholdMs)
        assertEquals(22 * 60, prefs.quietStartMinute)
        assertEquals(8 * 60, prefs.quietEndMinute)
        assertEquals(1, prefs.frequency.dailyCap())
        assertFalse(BackgroundActivityPause(app).isPaused)
    }

    @Test
    fun dailyCountResetsOnANewDay() {
        val prefs = CheckInPreferences(app)
        prefs.recordNotification("2026-11-10")
        prefs.recordNotification("2026-11-10")
        assertEquals(2, prefs.notificationsSent("2026-11-10"))
        assertEquals(0, prefs.notificationsSent("2026-11-11"))
    }
}
