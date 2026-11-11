package com.druk.lmplayground.computer.api

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.tools.CosConfirmGate

/**
 * Android side of [ApiApprovalRelay]: the "Computer API requests" notification.
 *
 * All user-facing text comes from a [Texts]; [Texts.from] reads it from string resources
 * (`R.string.computer_api_approval_*`, `R.string.cos_confirm_allow` / `_deny`).
 *
 * - [ensureChannel] creates channel [CHANNEL_ID] with `IMPORTANCE_HIGH` and name / description
 *   [Texts.channelName] / [Texts.channelDescription]; calling it again keeps the existing channel.
 * - [canPost] is false on API 33+ without the POST_NOTIFICATIONS grant, or when notifications are
 *   disabled for the app; the request then stays answerable only in-app (never auto-approved).
 * - [build] makes a notification on [CHANNEL_ID] with title `texts.title(request.action)` and the
 *   request summary (the command) as text, with `VISIBILITY_PRIVATE` and a public version titled
 *   [Texts.publicTitle] / [Texts.publicText] that never contains the summary. It has exactly two
 *   actions, [Texts.allow] then [Texts.deny]; each fires an immutable broadcast PendingIntent
 *   explicitly targeting [ApiApprovalReceiver] with action [ACTION_ALLOW] / [ACTION_DENY] and
 *   [EXTRA_KEY] = the request key. On API 31+ the Allow action requires device unlock
 *   (authentication required); Deny does not. It times out after
 *   [com.druk.lmplayground.tools.CosConfirmGate.DEFAULT_TIMEOUT_MS] and is not auto-cancel.
 * - [AndroidPoster.show] ensures the channel and posts [build] under [NOTIFICATION_ID] only when
 *   [canPost]; [AndroidPoster.cancel] removes [NOTIFICATION_ID]. Texts default to [Texts.from].
 */
object ApiApprovalNotification {
    const val CHANNEL_ID = "computer_api_approval"
    const val NOTIFICATION_ID = 0x4E0D_E0A1
    const val ACTION_ALLOW = "com.druk.lmplayground.computer.api.APPROVAL_ALLOW"
    const val ACTION_DENY = "com.druk.lmplayground.computer.api.APPROVAL_DENY"
    const val EXTRA_KEY = "com.druk.lmplayground.computer.api.EXTRA_APPROVAL_KEY"

    private const val REQUEST_ALLOW = 1
    private const val REQUEST_DENY = 2
    private const val REQUEST_OPEN = 3

    /** User-facing text of the channel and notification. */
    data class Texts(
        val channelName: String,
        val channelDescription: String,
        val title: (action: String) -> String,
        val publicTitle: String,
        val publicText: String,
        val allow: String,
        val deny: String,
    ) {
        companion object {
            fun from(context: Context) = Texts(
                channelName = context.getString(R.string.computer_api_approval_channel_name),
                channelDescription = context.getString(R.string.computer_api_approval_channel_description),
                title = { action -> context.getString(R.string.computer_api_approval_title, action) },
                publicTitle = context.getString(R.string.computer_api_approval_public_title),
                publicText = context.getString(R.string.computer_api_approval_public_text),
                allow = context.getString(R.string.cos_confirm_allow),
                deny = context.getString(R.string.cos_confirm_deny),
            )
        }
    }

    fun ensureChannel(context: Context, texts: Texts) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            texts.channelName,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = texts.channelDescription
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        nm.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun build(context: Context, request: ApiApprovalRelay.Request, texts: Texts): Notification {
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(texts.publicTitle)
            .setContentText(texts.publicText)
            .build()
        val allow = NotificationCompat.Action.Builder(
            0,
            texts.allow,
            actionIntent(context, ACTION_ALLOW, REQUEST_ALLOW, request.key),
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
        }.build()
        val deny = NotificationCompat.Action.Builder(
            0,
            texts.deny,
            actionIntent(context, ACTION_DENY, REQUEST_DENY, request.key),
        ).build()
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(texts.title(request.action))
            .setContentText(request.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(CosConfirmGate.DEFAULT_TIMEOUT_MS)
            .addAction(allow)
            .addAction(deny)
            .build()
    }

    // FLAG_CANCEL_CURRENT: a newer request's intents invalidate any older notification's actions.
    private fun actionIntent(context: Context, action: String, requestCode: Int, key: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ApiApprovalReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )

    class AndroidPoster(
        context: Context,
        private val texts: (Context) -> Texts = Texts::from,
    ) : ApiApprovalRelay.Poster {
        private val app = context.applicationContext

        override fun show(request: ApiApprovalRelay.Request) {
            if (!canPost(app)) return
            val t = texts(app)
            ensureChannel(app, t)
            try {
                NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, build(app, request, t))
            } catch (_: SecurityException) {
                // Permission revoked between canPost and notify: the in-app card still answers.
            }
        }

        override fun cancel(request: ApiApprovalRelay.Request) {
            NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
        }
    }
}

/**
 * Not-exported receiver for the notification's Allow / Deny actions.
 *
 * [handle]: [ApiApprovalNotification.ACTION_ALLOW] → `relay.answer(key, true)`,
 * [ApiApprovalNotification.ACTION_DENY] → `relay.answer(key, false)`, where key is
 * [ApiApprovalNotification.EXTRA_KEY]. A null intent, any other action, or a missing key does not
 * call the relay and returns false; otherwise returns what [ApiApprovalRelay.answer] returned.
 */
class ApiApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        handle(intent, ComputerApis.approvalRelay(context))
    }

    companion object {
        fun handle(intent: Intent?, relay: ApiApprovalRelay): Boolean {
            val approved = when (intent?.action) {
                ApiApprovalNotification.ACTION_ALLOW -> true
                ApiApprovalNotification.ACTION_DENY -> false
                else -> return false
            }
            val key = intent.getStringExtra(ApiApprovalNotification.EXTRA_KEY) ?: return false
            return relay.answer(key, approved)
        }
    }
}
