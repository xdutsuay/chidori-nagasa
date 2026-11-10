package com.druk.lmplayground.checkin

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.druk.lmplayground.App
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.coordinator.notifications.NeedsYouNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Intent action for a check-in notification tap. */
const val ACTION_OPEN_CHECK_IN = "com.xdutsuay.chidori.nagasa.action.OPEN_CHECK_IN"

const val ACTION_CHECK_IN_DONE = "com.xdutsuay.chidori.nagasa.action.CHECK_IN_DONE"
const val ACTION_CHECK_IN_PARK = "com.xdutsuay.chidori.nagasa.action.CHECK_IN_PARK"
const val ACTION_CHECK_IN_LATER = "com.xdutsuay.chidori.nagasa.action.CHECK_IN_LATER"

const val EXTRA_CHECK_IN_SOURCE = "com.xdutsuay.chidori.nagasa.extra.CHECK_IN_SOURCE"
const val EXTRA_CHECK_IN_SOURCE_ID = "com.xdutsuay.chidori.nagasa.extra.CHECK_IN_SOURCE_ID"
const val EXTRA_CHECK_IN_SESSION_ID = "com.xdutsuay.chidori.nagasa.extra.CHECK_IN_SESSION_ID"
const val EXTRA_CHECK_IN_INSTANCE_ID = "com.xdutsuay.chidori.nagasa.extra.CHECK_IN_INSTANCE_ID"
const val EXTRA_CHECK_IN_RUN_ID = "com.xdutsuay.chidori.nagasa.extra.CHECK_IN_RUN_ID"
const val EXTRA_CHECK_IN_ITEM_IDS = "com.xdutsuay.chidori.nagasa.extra.CHECK_IN_ITEM_IDS"

/**
 * Where a check-in notification tap should land. Pure so JVM tests can parse
 * intents without Robolectric.
 */
data class CheckInDeepLink(
    val source: String,
    val sourceId: String,
    val sessionId: String?,
    val instanceId: String?,
    val runId: String?,
)

fun parseCheckInDeepLink(
    action: String?,
    source: String?,
    sourceId: String?,
    sessionId: String?,
    instanceId: String?,
    runId: String?,
): CheckInDeepLink? {
    if (action != ACTION_OPEN_CHECK_IN) return null
    val src = source?.takeUnless { it.isBlank() } ?: return null
    val id = sourceId?.takeUnless { it.isBlank() } ?: return null
    return CheckInDeepLink(
        source = src,
        sourceId = id,
        sessionId = sessionId?.takeUnless { it.isBlank() },
        instanceId = instanceId?.takeUnless { it.isBlank() },
        runId = runId?.takeUnless { it.isBlank() },
    )
}

/**
 * Held until [com.druk.lmplayground.conversation.ConversationFragment] can
 * open the chat session named by a check-in tap.
 */
object CheckInPendingHold {
    private val _pending = MutableStateFlow<CheckInDeepLink?>(null)
    val pending: StateFlow<CheckInDeepLink?> = _pending.asStateFlow()

    fun offer(link: CheckInDeepLink) {
        _pending.value = link
    }

    fun consume() {
        _pending.value = null
    }
}

/**
 * Posts the single check-in notification on the existing needs-you channel.
 * One id, so a later run replaces the previous nudge instead of stacking.
 * Done / Park / Later apply to every bundled item.
 */
object CheckInNotifier {
    const val NOTIFICATION_ID = 0x4E0D_E033

    fun post(context: Context, items: List<OpenItemEntity>, text: String) {
        if (items.isEmpty()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NeedsYouNotification.ensureChannel(context)
        val first = items.first()
        val probe = ProbeDescriptor.fromJson(first.probeJson)
        val content = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHECK_IN
            putExtra(EXTRA_CHECK_IN_SOURCE, first.source)
            putExtra(EXTRA_CHECK_IN_SOURCE_ID, first.sourceId)
            putExtra(EXTRA_CHECK_IN_SESSION_ID, first.sessionId)
            putExtra(EXTRA_CHECK_IN_INSTANCE_ID, probe?.instanceId)
            putExtra(EXTRA_CHECK_IN_RUN_ID, probe?.runId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            content,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ids = ArrayList(items.map { it.id })
        val notification = NotificationCompat.Builder(context, NeedsYouNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.check_in_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(action(context, ACTION_CHECK_IN_DONE, R.string.check_in_action_done, REQUEST_DONE, ids))
            .addAction(action(context, ACTION_CHECK_IN_PARK, R.string.check_in_action_park, REQUEST_PARK, ids))
            .addAction(action(context, ACTION_CHECK_IN_LATER, R.string.check_in_action_later, REQUEST_LATER, ids))
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun action(
        context: Context,
        action: String,
        labelRes: Int,
        requestCode: Int,
        ids: ArrayList<String>,
    ): NotificationCompat.Action {
        val intent = Intent(context, CheckInActionReceiver::class.java).apply {
            this.action = action
            putStringArrayListExtra(EXTRA_CHECK_IN_ITEM_IDS, ids)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_launcher_monochrome,
            context.getString(labelRes),
            pi,
        ).build()
    }

    private const val REQUEST_CONTENT = 0x4330
    private const val REQUEST_DONE = 0x4331
    private const val REQUEST_PARK = 0x4332
    private const val REQUEST_LATER = 0x4333
}

/**
 * Notification actions. Done, Park, and Later update every item that was
 * bundled into the nudge. Later keeps the row RAISED and refreshes the
 * raise baseline so the dedupe gate stays quiet until something changes.
 */
class CheckInActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayListExtra(EXTRA_CHECK_IN_ITEM_IDS).orEmpty()
        if (ids.isEmpty()) return
        val action = when (intent.action) {
            ACTION_CHECK_IN_DONE -> CheckInUserAction.DONE
            ACTION_CHECK_IN_PARK -> CheckInUserAction.PARK
            ACTION_CHECK_IN_LATER -> CheckInUserAction.LATER
            else -> return
        }
        val pending = goAsync()
        val app = context.applicationContext as? App
        if (app == null) {
            pending.finish()
            return
        }
        scope.launch {
            try {
                app.openItemRepository.applyUserAction(ids, action, System.currentTimeMillis())
                NotificationManagerCompat.from(context).cancel(CheckInNotifier.NOTIFICATION_ID)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
