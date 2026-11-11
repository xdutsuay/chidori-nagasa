package com.xdutsuay.chidori.coordinator.notifications

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
import com.xdutsuay.chidori.MainActivity
import com.xdutsuay.chidori.R

/**
 * Transient system notification for needs_you run events (KMA-288) —
 * mirrors NodeNotification's channel posture (own channel, no lights/vibrate
 * ceremony) but as one-shot alerts, not an ongoing service notification.
 *
 * The content Intent carries [ACTION_OPEN_NEEDS_YOU] plus instance/run ids so
 * a tap opens the monitor run detail (daily-use deep link), not bare home.
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

    fun post(context: Context, instanceId: String, runId: String, reason: String) {
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
            action = ACTION_OPEN_NEEDS_YOU
            putExtra(EXTRA_NEEDS_YOU_INSTANCE_ID, instanceId)
            putExtra(EXTRA_NEEDS_YOU_RUN_ID, runId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // requestCode keyed by runId hash so concurrent needs_you notifications
        // don't clobber each other's PendingIntent extras.
        val requestCode = runId.hashCode()
        val pi = PendingIntent.getActivity(
            context, requestCode, launch,
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
        // Per-run notification id so two blocked runs both stay visible.
        val notifyId = NOTIFICATION_ID xor (runId.hashCode() and 0x7FFF_FFFF)
        NotificationManagerCompat.from(context).notify(notifyId, notification)
    }
}
