package com.druk.lmplayground.computer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R
import com.druk.lmplayground.computer.desktop.LocalDesktopBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service for long COS exec / ensureRootfs / **desktop session** so Android
 * is less likely to kill proot/Xvfb when the UI is backgrounded. Opt-in Computer feature only.
 */
class CosComputerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** True while [ACTION_DESKTOP] is holding the FGS open for a running desktop. */
    @Volatile private var desktopKeepAlive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                desktopKeepAlive = false
                LocalDesktopBridge.peek()?.stopAsync("cos-service-stop")
                LocalComputerRuntime.create(this).stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DESKTOP -> {
                ensureChannel()
                desktopKeepAlive = true
                startAsForeground(getString(R.string.cos_notification_desktop))
                return START_STICKY
            }
            ACTION_DESKTOP_END -> {
                desktopKeepAlive = false
                // If an exec/prune job is still running, keep the notification for that work.
                if (job?.isActive != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    startAsForeground(getString(R.string.cos_notification_running))
                }
                return START_NOT_STICKY
            }
            ACTION_PRUNE -> {
                ensureChannel()
                startAsForeground("Pruning COS storage…")
                job?.cancel()
                job = scope.launch {
                    LocalComputerRuntime.create(this@CosComputerService).pruneIfNeeded()
                    if (!desktopKeepAlive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        startAsForeground(getString(R.string.cos_notification_desktop))
                    }
                }
                return START_STICKY
            }
            else -> {
                val argv = intent?.getStringArrayListExtra(EXTRA_ARGV).orEmpty()
                ensureChannel()
                startAsForeground(getString(R.string.cos_notification_running))
                job?.cancel()
                job = scope.launch {
                    val runtime = LocalComputerRuntime.create(this@CosComputerService)
                    if (argv.isNotEmpty()) {
                        runtime.exec(argv)
                    }
                    if (!desktopKeepAlive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        startAsForeground(getString(R.string.cos_notification_desktop))
                    }
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(content: String) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cos_notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.cos_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "cos_computer"
        const val NOTIF_ID = 71027
        const val ACTION_STOP = "com.druk.lmplayground.computer.STOP"
        const val ACTION_PRUNE = "com.druk.lmplayground.computer.PRUNE"
        const val ACTION_DESKTOP = "com.druk.lmplayground.computer.DESKTOP"
        const val ACTION_DESKTOP_END = "com.druk.lmplayground.computer.DESKTOP_END"
        const val EXTRA_ARGV = "argv"

        fun startExec(context: Context, argv: List<String>) {
            val i = Intent(context, CosComputerService::class.java)
                .putStringArrayListExtra(EXTRA_ARGV, ArrayList(argv))
            context.startForegroundService(i)
        }

        fun startPrune(context: Context) {
            val i = Intent(context, CosComputerService::class.java).setAction(ACTION_PRUNE)
            context.startForegroundService(i)
        }

        /** Hold FGS while the CINDI/COS desktop (Xvfb) is Running. */
        fun startDesktop(context: Context) {
            val i = Intent(context, CosComputerService::class.java).setAction(ACTION_DESKTOP)
            context.startForegroundService(i)
        }

        fun endDesktop(context: Context) {
            val i = Intent(context, CosComputerService::class.java).setAction(ACTION_DESKTOP_END)
            context.startService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, CosComputerService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
