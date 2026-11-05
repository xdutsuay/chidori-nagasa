package com.druk.lmplayground.coordinator.node

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class NodeListenEndpoint(
    val port: Int,
    val dataPlaneToken: String,
    /** Unicast host the socket is bound to (LAN IPv4 or loopback). */
    val bindHost: String = NodeOpenAiServer.LOOPBACK,
)

/**
 * Foreground [dataSync] host for [NodeOpenAiServer].
 *
 * Binding a long-lived inbound socket outside a foreground service lets the OS
 * kill the process (KMA-97). Mirrors [com.druk.lmplayground.inference.LlamaService]'s
 * promote-to-foreground pattern, but owned by the main process so the registration
 * path can await the listen port synchronously.
 *
 * [startForeground] runs in [onCreate] so a STOP (or a targetSdk-35 typed
 * [Context.startService] of this component) cannot bring the service up without
 * promoting — that was ForegroundServiceDidNotStartInTimeException on RMX3750.
 */
class NodeService : Service() {

    private var server: NodeOpenAiServer? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        hosted.set(true)
        NodeNotification.ensureChannel(this)
        promoteToForeground()
        Log.i(TAG, "NodeService.onCreate pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                tearDown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                try {
                    val host = intent?.getStringExtra(EXTRA_BIND_HOST)?.takeIf { it.isNotBlank() }
                        ?: NodeOpenAiServer.LOOPBACK
                    ensureServerStarted(host)
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to start node socket", t)
                    failPending(t)
                    tearDown()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tearDown()
        hosted.set(false)
        startRequested.set(false)
        super.onDestroy()
    }

    private fun promoteToForeground() {
        if (isForeground) return
        val notification = NodeNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NodeNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NodeNotification.NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun ensureServerStarted(bindHost: String) {
        if (server != null) {
            val port = listenPort.get()
            val token = listenToken.get()
            val host = listenHost.get()
            if (port > 0 && token.isNotEmpty()) {
                completePending(NodeListenEndpoint(port, token, host))
            }
            return
        }
        require(!NodeOpenAiServer.isWildcard(bindHost)) {
            "Refusing plaintext wildcard bind ($bindHost)"
        }
        val token = NodeOpenAiServer.newDataPlaneToken()
        val srv = NodeOpenAiServer(token, bindHost = bindHost) { NodeInferenceHub.bridge }
        val port = srv.start()
        server = srv
        listenPort.set(port)
        listenToken.set(token)
        listenHost.set(bindHost)
        completePending(NodeListenEndpoint(port, token, bindHost))
        Log.i(TAG, "NodeOpenAiServer listening on $bindHost:$port (not 0.0.0.0)")
    }

    private fun tearDown() {
        try {
            server?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "server stop failed", t)
        }
        server = null
        listenPort.set(-1)
        listenToken.set("")
        listenHost.set(NodeOpenAiServer.LOOPBACK)
        if (isForeground) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            isForeground = false
        }
    }

    companion object {
        private const val TAG = "NodeService"
        private const val ACTION_START = "com.druk.lmplayground.action.NODE_START"
        private const val ACTION_STOP = "com.druk.lmplayground.action.NODE_STOP"
        private const val START_TIMEOUT_MS = 15_000L

        private val mu = Mutex()
        private const val EXTRA_BIND_HOST = "bind_host"

        private val listenPort = AtomicInteger(-1)
        private val listenToken = AtomicReference("")
        private val listenHost = AtomicReference(NodeOpenAiServer.LOOPBACK)
        private val startRequested = AtomicBoolean(false)
        private val hosted = AtomicBoolean(false)
        @Volatile private var pendingPort: CompletableDeferred<NodeListenEndpoint>? = null

        /**
         * Starts the foreground service (if needed) and returns listen port +
         * data-plane bearer. [bindHost] must be LAN unicast or loopback — never
         * `0.0.0.0`. Throws if the service fails to bind within [START_TIMEOUT_MS].
         */
        suspend fun startAndAwaitPort(
            context: Context,
            bindHost: String = NodeOpenAiServer.LOOPBACK,
        ): NodeListenEndpoint = mu.withLock {
            require(!NodeOpenAiServer.isWildcard(bindHost)) {
                "Refusing plaintext wildcard bind ($bindHost)"
            }
            val existingPort = listenPort.get()
            val existingToken = listenToken.get()
            val existingHost = listenHost.get()
            if (existingPort > 0 && existingToken.isNotEmpty() && existingHost == bindHost) {
                return NodeListenEndpoint(existingPort, existingToken, existingHost)
            }

            val deferred = CompletableDeferred<NodeListenEndpoint>()
            pendingPort = deferred
            startRequested.set(true)
            val app = context.applicationContext
            val intent = Intent(app, NodeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BIND_HOST, bindHost)
            ContextCompat.startForegroundService(app, intent)
            try {
                withTimeout(START_TIMEOUT_MS) { deferred.await() }
            } catch (t: Throwable) {
                pendingPort = null
                stop(app)
                throw t
            }
        }

        fun isRunning(): Boolean = hosted.get() || startRequested.get() || listenPort.get() > 0

        /**
         * Tear down a running host. No-op if we never issued a start — must not
         * [Context.startService] a cold [NodeService] (typed FGS on targetSdk 35).
         */
        fun stop(context: Context) {
            if (!isRunning()) {
                pendingPort?.cancel()
                pendingPort = null
                listenPort.set(-1)
                listenToken.set("")
                listenHost.set(NodeOpenAiServer.LOOPBACK)
                return
            }
            val app = context.applicationContext
            startRequested.set(false)
            listenPort.set(-1)
            listenToken.set("")
            listenHost.set(NodeOpenAiServer.LOOPBACK)
            pendingPort?.cancel()
            pendingPort = null
            app.stopService(Intent(app, NodeService::class.java))
        }

        private fun completePending(endpoint: NodeListenEndpoint) {
            pendingPort?.complete(endpoint)
            pendingPort = null
        }

        private fun failPending(t: Throwable) {
            pendingPort?.completeExceptionally(t)
            pendingPort = null
        }
    }
}
