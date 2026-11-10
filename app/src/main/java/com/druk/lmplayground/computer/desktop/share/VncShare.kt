package com.druk.lmplayground.computer.desktop.share

import com.druk.lmplayground.computer.desktop.DesktopBridge
import com.druk.lmplayground.computer.desktop.DesktopPackages
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.DesktopStream
import com.druk.lmplayground.computer.desktop.ShareLease
import com.druk.lmplayground.computer.desktop.ShellQuote
import com.druk.lmplayground.computer.desktop.ViewerEndpoint
import com.druk.lmplayground.computer.desktop.ViewerScope
import com.druk.lmplayground.computer.desktop.ViewerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds

/**
 * Developer-only VNC viewer for the COS desktop: `x11vnc` inside Alpine, bound to
 * 127.0.0.1 (`-localhost`), reached from a computer with `adb forward`. Never on the
 * LAN. Runs as a [DesktopStream], so desktop stop tears it down like any guest
 * process; the share also ends on [stop], when the lease runs out, or when the
 * desktop leaves [DesktopState.Running].
 */
class VncShare(
    private val bridge: DesktopBridge,
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val password: () -> String = ::randomPassword,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ViewerState>(ViewerState.Off)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var stream: DesktopStream? = null
    private var jobs: List<Job> = emptyList()
    @Volatile private var stopping = false

    suspend fun start(
        lease: ShareLease = ShareLease(),
        scope: ViewerScope = ViewerScope.LOOPBACK,
    ): Result<ViewerEndpoint> = mutex.withLock {
        (_state.value as? ViewerState.Shared)?.let { return Result.success(it.endpoint) }
        if (scope != ViewerScope.LOOPBACK) {
            return Result.failure(UnsupportedOperationException("LAN sharing is not available yet"))
        }
        val running = bridge.state.value as? DesktopState.Running
            ?: return Result.failure(IllegalStateException("desktop not running — start it first"))
        _state.value = ViewerState.Starting

        if (bridge.execInDesktop("command -v x11vnc").exitCode != 0) {
            val args = DesktopPackages.VIEWER_PINS.map { (n, v) -> ShellQuote.quote("$n=$v") }
            val r = bridge.execInDesktop("apk add " + args.joinToString(" "), INSTALL_TIMEOUT_MS.milliseconds)
            if (r.exitCode != 0) return fail("x11vnc install failed (exit ${r.exitCode}): ${tail(r.stderr + r.stdout)}")
        }

        val pw = password()
        val pwCmd = "umask 077 && mkdir -p ${PASSWORD_DIR} && printf '%s\\n' ${ShellQuote.quote(pw)} > $PASSWORD_FILE"
        val w = bridge.execInDesktop(pwCmd)
        if (w.exitCode != 0) return fail("could not write the viewer password (exit ${w.exitCode})")

        val argv = listOf(
            "x11vnc", "-display", running.display, "-auth", running.xauthorityGuestPath,
            "-localhost", "-rfbport", port.toString(), "-passwdfile", "rm:$PASSWORD_FILE",
            // proot on Android has no SysV shared memory (shmget: ENOSYS).
            "-forever", "-shared", "-noxdamage", "-noshm",
        )
        val s = try {
            bridge.openDesktopStream("exec " + ShellQuote.join(argv))
        } catch (e: Exception) {
            return fail("x11vnc did not start: ${e.message}")
        }

        val ready = CompletableDeferred<Int>()
        val errLines = ArrayDeque<String>()
        fun onLine(line: String) {
            PORT_LINE.matchEntire(line.trim())?.let { ready.complete(it.groupValues[1].toInt()); return }
            synchronized(errLines) { errLines.addLast(line); while (errLines.size > 5) errLines.removeFirst() }
        }
        val readers = listOf(
            this.scope.launch { s.stdout.collect { onLine(it) } },
            this.scope.launch { s.stderr.collect { onLine(it) } },
        )
        val exitWatch = this.scope.launch { s.exitCode.first { it != null }; ready.cancel() }
        val boundPort = withTimeoutOrNull(readyTimeoutMs) { runCatching { ready.await() }.getOrNull() }
        exitWatch.cancel()
        if (boundPort == null) {
            readers.forEach { it.cancel() }
            runCatching { s.close(START_FAILED_REASON) }
            val why = synchronized(errLines) { errLines.joinToString(" | ") }
            return fail("x11vnc did not start" + if (why.isNotBlank()) ": $why" else "")
        }

        val endpoint = ViewerEndpoint(LOOPBACK_HOST, boundPort, pw, nowMs() + lease.maxMs)
        stream = s
        stopping = false
        _state.value = ViewerState.Shared(endpoint)
        jobs = readers + listOf(
            this.scope.launch { delay(lease.maxMs); stop(LEASE_REASON) },
            this.scope.launch {
                s.exitCode.first { it != null }
                if (!stopping) {
                    mutex.withLock { if (stream === s) { clear(); _state.value = ViewerState.Error("viewer exited (code ${s.exitCode.value})") } }
                }
            },
            this.scope.launch {
                bridge.state.first { it !is DesktopState.Running }
                stop(DESKTOP_STOP_REASON)
            },
        )
        Result.success(endpoint)
    }

    suspend fun stop(reason: String = STOP_REASON) {
        val s: DesktopStream?
        val old: List<Job>
        mutex.withLock {
            s = stream
            old = jobs
            if (s == null && _state.value !is ViewerState.Shared) return
            stopping = true
            stream = null
            jobs = emptyList()
            _state.value = ViewerState.Off
        }
        runCatching { s?.close(reason) }
        old.forEach { it.cancel() }
    }

    private fun clear() {
        stream = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private fun fail(message: String): Result<ViewerEndpoint> {
        _state.value = ViewerState.Error(message)
        return Result.failure(IllegalStateException(message))
    }

    private fun tail(s: String) = s.trim().lines().takeLast(3).joinToString(" | ")

    companion object {
        const val DEFAULT_PORT = 5901
        const val READY_TIMEOUT_MS = 15_000L
        const val INSTALL_TIMEOUT_MS = 10L * 60 * 1000
        const val STOP_REASON = "viewer-stop"
        const val LEASE_REASON = "viewer-lease-expired"
        const val DESKTOP_STOP_REASON = "desktop-stop"
        const val START_FAILED_REASON = "viewer-start-failed"
        const val PASSWORD_DIR = "/tmp/cos-vnc"
        const val PASSWORD_FILE = "$PASSWORD_DIR/passwd"
        const val LOOPBACK_HOST = "127.0.0.1"

        /** RFB VNC auth only uses the first 8 characters. */
        private const val PASSWORD_LEN = 8
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val PORT_LINE = Regex("^PORT=(\\d+)$")
        private val random = SecureRandom()

        fun randomPassword(): String =
            buildString { repeat(PASSWORD_LEN) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}
