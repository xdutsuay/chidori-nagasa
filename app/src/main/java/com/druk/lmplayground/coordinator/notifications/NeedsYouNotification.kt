package com.druk.lmplayground.coordinator.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R

/**
 * Transient system notification for needs_you run events (KMA-288) —
 * mirrors NodeNotification's channel posture (own channel, no lights/vibrate
 * ceremony) but as one-shot alerts, not an ongoing service notification.
 */
internal object NeedsYouNotification {
    const val CHANNEL_ID = "chidori_needs_you"
    const val NOTIFICATION_ID = 0x4E0D_E002

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.needs_you_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.needs_you_notification_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun post(context: Context, runId: String, reason: String) {
        // Android 13+ gates notify() behind the runtime POST_NOTIFICATIONS
        // permission; skip rather than crash when the user hasn't granted it
        // (the monitor screen still shows the run's NEEDS_YOU state).
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = reason.ifBlank { context.getString(R.string.needs_you_notification_default_text) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.needs_you_notification_title))
            .setContentText(context.getString(R.string.needs_you_notification_text, runId, text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.needs_you_notification_text, runId, text)),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
