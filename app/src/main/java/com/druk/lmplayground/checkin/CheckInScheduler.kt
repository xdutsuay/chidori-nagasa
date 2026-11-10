package com.druk.lmplayground.checkin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the unique PeriodicWork `proactive-checkin`. Download work uses other
 * names. Nothing else should enqueue [CheckInWorker]. When check-ins are
 * off the unique work is cancelled, so a default install does not schedule it.
 */
object CheckInScheduler {
    const val WORK_NAME = "proactive-checkin"

    fun sync(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val prefs = CheckInPreferences(context)
        if (!prefs.enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val hours = prefs.frequency.intervalHours()
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CheckInWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
