package com.druk.lmplayground.checkin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CheckInSchedulerTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.getSharedPreferences(CheckInPreferences.PREFS, 0).edit().clear().commit()
    }

    @Test
    fun enabledWorkIsUniqueBatteryNotLowAndCancelledWhenDisabled() {
        val prefs = CheckInPreferences(app)
        prefs.enabled = true
        prefs.frequency = CheckInFrequency.SIX_HOURS
        CheckInScheduler.sync(app)
        val workManager = WorkManager.getInstance(app)
        val enqueued = workManager.getWorkInfosForUniqueWork(CheckInScheduler.WORK_NAME).get()
        assertEquals(1, enqueued.size)
        assertEquals(CheckInScheduler.WORK_NAME, enqueued.single().tags.first { it == CheckInScheduler.WORK_NAME })
        assertTrue(enqueued.single().constraints.requiresBatteryNotLow())
        assertTrue(enqueued.single().state == WorkInfo.State.ENQUEUED)

        prefs.enabled = false
        CheckInScheduler.sync(app)
        val cancelled = workManager.getWorkInfosForUniqueWork(CheckInScheduler.WORK_NAME).get()
        assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
    }
}
