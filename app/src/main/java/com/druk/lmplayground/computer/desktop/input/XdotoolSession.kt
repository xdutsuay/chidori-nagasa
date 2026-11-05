package com.druk.lmplayground.computer.desktop.input

import com.druk.lmplayground.computer.CosAgentBridge
import com.druk.lmplayground.computer.desktop.CosExecResult
import com.druk.lmplayground.computer.desktop.DesktopBridge
import com.druk.lmplayground.computer.desktop.DesktopGeometry
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.DesktopStream
import com.druk.lmplayground.computer.desktop.ShellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.WeakHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * One long-lived xdotool per desktop session, fed through
 * [DesktopBridge.openDesktopStream] (see docs/COS_DESKTOP_INPUT.md).
 *
 * Modes, chosen when the stream is (re)started:
 * - [Mode.SCRIPT]: `exec xdotool -`, one script line per command ([XdotoolScript]);
 *   probed with `getmouselocation`, whose stdout line (`x:.. y:.. screen:.. window:..`)
 *   is also the FIFO sync marker for [run].
 * - [Mode.SHELL]: fallback when the script probe fails: `exec /bin/sh` reading one
 *   `xdotool …` line per command ([ShellQuote]); sync marker `echo "@@cos-sync $?"`
 *   (so [run] gets the real exit code).
 * - [Mode.EXEC]: the bridge has no stream support (default [DesktopBridge.openDesktopStream]),
 *   the stream cannot start, or it restarted too often: one [DesktopBridge.execInDesktop]
 *   per command (the CP2 cos_gui path).
 *
 * Lifecycle: nothing starts until the first command (lazy); a dead stream is
 * restarted on the next command with exponential backoff; more than
 * [Config.maxRestarts] deaths within [Config.restartWindowMs] degrade to EXEC
 * until the desktop is started again. The stream is closed (ProotTerminator path in
 * the bridge) when the desktop leaves [DesktopState.Running], on [close], and when
 * a [run] is cancelled while its command is in flight (chat Stop, reason
 * [CosAgentBridge.CHAT_STOP]) — that kills a long `type` immediately.
 *
 * Queue: bounded ([Config.queueCapacity]). [send] (UI, never blocks) coalesces a
 * [InputCommand.MouseMove] into a still-queued trailing move and, when full, drops
 * the oldest queued move or else rejects the command (returns false). [submit]
 * suspends until there is room. [run] (cos_gui) is queued in order with UI input and
 * waits for its sync marker.
 */
class XdotoolSession internal constructor(
    private val bridge: DesktopBridge,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    data class Config(
        val queueCapacity: Int = 64,
        val probeTimeoutMs: Long = 5_000,
        val syncTimeoutMs: Long = 20_000,
        /** stderr lines may arrive just after the stdout marker (separate pipes). */
        val stderrGraceMs: Long = 30,
        val maxRestarts: Int = 5,
        val restartWindowMs: Long = 30_000,
        val backoffBaseMs: Long = 100,
        val backoffMaxMs: Long = 2_000,
        val execTimeoutMs: Long = 20_000,
    )

    enum class Mode { NONE, SCRIPT, SHELL, EXEC }

    /** [sync] completes with null when the caller must run the command itself via execInDesktop. */
    private class Item(val cmd: InputCommand?, val argv: List<String>, val sync: CompletableDeferred<CosExecResult?>?) {
        @Volatile var started = false
    }

    private class Live(val stream: DesktopStream, val mode: Mode, val markers: Channel<String>) {
        @Volatile var jobs: List<Job> = emptyList()
        val stderr = ArrayList<String>()
        @Volatile var closedByUs = false
        fun alive() = stream.exitCode.value == null && !closedByUs
        fun takeStderr(): String = synchronized(stderr) { stderr.joinToString("\n").also { stderr.clear() } }
        fun clearStderr() = synchronized(stderr) { stderr.clear() }
    }

    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private val queued = MutableStateFlow(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val streamMutex = Mutex()
    private var writer: Job? = null
    private var watcher: Job? = null
    @Volatile private var live: Live? = null
    private val deaths = ArrayDeque<Long>()
    @Volatile private var degraded = false
    @Volatile private var closed = false

    @Volatile var mode: Mode = Mode.NONE
        private set

    /** Streams opened so far (tests / diagnostics). */
    @Volatile var streamsOpened: Int = 0
        private set

    @Volatile var droppedMoves: Int = 0
        private set

    @Volatile var rejected: Int = 0
        private set

    val queueSize: Int get() = queued.value

    private fun geometry(): DesktopGeometry? =
        (bridge.state.value as? DesktopState.Running)?.let { DesktopGeometry(it.width, it.height) }

    /** UI path: enqueue without blocking. False when the desktop is not running or the queue is full. */
    fun send(cmd: InputCommand): Boolean {
        if (closed) return false
        val g = geometry() ?: return false
        val argv = cmd.toArgv(g)
        synchronized(lock) {
            val tail = queue.lastOrNull()
            if (cmd is InputCommand.MouseMove && tail != null && !tail.started && tail.sync == null && tail.cmd is InputCommand.MouseMove) {
                queue[queue.lastIndex] = Item(cmd, argv, null)
                droppedMoves++
                return true
            }
            if (cmd is InputCommand.MouseMoveRelative && tail != null && !tail.started && tail.sync == null &&
                tail.cmd is InputCommand.MouseMoveRelative
            ) {
                val a = tail.cmd as InputCommand.MouseMoveRelative
                val merged = InputCommand.MouseMoveRelative(a.dx + cmd.dx, a.dy + cmd.dy)
                queue[queue.lastIndex] = Item(merged, merged.toArgv(g), null)
                droppedMoves++
                return true
            }
            if (queue.size >= config.queueCapacity) {
                val i = queue.indexOfFirst {
                    !it.started && it.sync == null &&
                        (it.cmd is InputCommand.MouseMove || it.cmd is InputCommand.MouseMoveRelative)
                }
                if (i >= 0) { queue.removeAt(i); droppedMoves++ } else { rejected++; return false }
            }
            queue.addLast(Item(cmd, argv, null))
            queued.value = queue.size
        }
        kick()
        return true
    }

    /** Like [send] but suspends while the queue is full instead of rejecting. */
    suspend fun submit(cmd: InputCommand) {
        while (!send(cmd)) {
            if (closed || geometry() == null) return
            queued.first { it < config.queueCapacity }
        }
    }

    /**
     * cos_gui path: runs xdotool [argv] (no leading `xdotool`) after everything already
     * queued and returns once it has been executed. SCRIPT mode reports exit 0 plus any
     * stderr xdotool printed meanwhile (script mode has no per-command status); SHELL and
     * EXEC report the real exit code. Timeout → 124. Cancellation kills the stream.
     */
    suspend fun run(argv: List<String>): CosExecResult {
        if (closed) return execDirect(argv)
        val done = CompletableDeferred<CosExecResult?>()
        val item = Item(null, argv, done)
        synchronized(lock) { queue.addLast(item); queued.value = queue.size }
        kick()
        val result = try {
            done.await()
        } catch (e: CancellationException) {
            val wasStarted = synchronized(lock) {
                if (!item.started) { queue.remove(item); queued.value = queue.size; false } else true
            }
            if (wasStarted) withContext(NonCancellable) { abort(e.message ?: CosAgentBridge.CHAT_STOP) }
            throw e
        }
        // No stream: exec in the CALLER's coroutine so chat Stop cancels it (ProotTerminator).
        return result ?: execDirect(argv)
    }

    private suspend fun execDirect(argv: List<String>): CosExecResult =
        bridge.execInDesktop(ShellQuote.join(listOf(XDOTOOL) + argv), config.execTimeoutMs.milliseconds)

    /** Closes the stream and drops queued UI input; the session restarts lazily on the next command. */
    suspend fun stopStream(reason: String = DesktopStream.CLOSE_REASON) {
        failQueued("desktop input stopped ($reason)")
        abort(reason)
    }

    /** Ends the session for good (desktop session end). */
    suspend fun close(reason: String = DesktopStream.CLOSE_REASON) {
        closed = true
        watcher?.cancel()
        stopStream(reason)
        writer?.cancel()
    }

    private fun failQueued(msg: String) {
        val dropped = synchronized(lock) {
            val l = queue.filter { !it.started }
            queue.removeAll(l)
            queued.value = queue.size
            l
        }
        dropped.forEach { it.sync?.complete(CosExecResult(1, "", msg)) }
    }

    private suspend fun abort(reason: String) {
        val l = live ?: return
        l.closedByUs = true
        live = null
        log("xdotool session close reason=$reason")
        runCatching { l.stream.close(reason) }
        l.jobs.forEach { it.cancel() }
        l.markers.close()
    }

    private fun kick() {
        synchronized(lock) {
            if (writer?.isActive != true) {
                writer = scope.launch { writeLoop() }
            }
            if (watcher == null) watcher = scope.launch { watchDesktop() }
        }
        wake.trySend(Unit)
    }

    private suspend fun watchDesktop() {
        var wasRunning = bridge.state.value is DesktopState.Running
        bridge.state.collect { s ->
            val running = s is DesktopState.Running
            if (wasRunning && !running) stopStream("desktop-stop")
            if (!wasRunning && running) { degraded = false; synchronized(deaths) { deaths.clear() } }
            wasRunning = running
        }
    }

    private suspend fun writeLoop() {
        while (true) {
            val item = synchronized(lock) {
                queue.firstOrNull()?.also { it.started = true }
            }
            if (item == null) { wake.receive(); continue }
            try {
                process(item)
            } catch (e: CancellationException) {
                item.sync?.complete(CosExecResult(130, "", "cancelled"))
                throw e
            } catch (e: Exception) {
                item.sync?.complete(CosExecResult(1, "", "xdotool session: ${e.message ?: e::class.simpleName}"))
            } finally {
                synchronized(lock) { queue.remove(item); queued.value = queue.size }
            }
        }
    }

    private suspend fun process(item: Item) {
        val l = ensureLive()
        if (l == null) {
            if (item.sync != null) item.sync.complete(null) else execDirect(item.argv)
            return
        }
        val lines = when (l.mode) {
            Mode.SCRIPT -> try {
                XdotoolScript.lines(item.argv)
            } catch (e: XdotoolScript.UnencodableException) {
                item.sync?.complete(CosExecResult(2, "", "cannot encode for xdotool script mode: ${e.message}"))
                return
            }
            else -> listOf(ShellQuote.join(listOf(XDOTOOL) + item.argv))
        }
        val sync = item.sync
        if (sync != null) l.clearStderr()
        val payload = buildString {
            lines.forEach { append(it).append('\n') }
            if (sync != null) append(marker(l.mode)).append('\n')
        }
        try {
            l.stream.write(payload)
        } catch (e: Exception) {
            if (!l.closedByUs) noteDeath(l)
            sync?.complete(CosExecResult(1, "", "xdotool session write failed: ${e.message}"))
            return
        }
        if (sync == null) return
        val line = try {
            withTimeoutOrNull(config.syncTimeoutMs) { l.markers.receive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // channel closed: process died or was closed
            if (!l.closedByUs) noteDeath(l)
            val code = l.stream.exitCode.value ?: 1
            sync.complete(CosExecResult(if (l.closedByUs) 130 else code, "", (l.takeStderr() + "\nxdotool session ended").trim()))
            return
        }
        if (line == null) {
            abort("desktop-input-timeout")
            sync.complete(CosExecResult(124, "", (l.takeStderr() + "\ntimed out").trim()))
            return
        }
        delay(config.stderrGraceMs)
        val err = l.takeStderr()
        val code = if (l.mode == Mode.SHELL) line.removePrefix(SHELL_MARKER).trim().toIntOrNull() ?: 1 else 0
        sync.complete(CosExecResult(code, "", err))
    }

    private fun noteDeath(l: Live) {
        if (live === l) live = null
        l.jobs.forEach { it.cancel() }
        l.markers.close()
        synchronized(deaths) {
            val t = now()
            deaths.addLast(t)
            while (deaths.isNotEmpty() && t - deaths.first() > config.restartWindowMs) deaths.removeFirst()
            if (deaths.size > config.maxRestarts) {
                degraded = true
                log("xdotool session: ${deaths.size} deaths in ${config.restartWindowMs} ms, falling back to exec")
            }
        }
    }

    /** Current stream, (re)starting it if needed; null → use execInDesktop. */
    private suspend fun ensureLive(): Live? = streamMutex.withLock {
        live?.let { if (it.alive()) return it else if (!it.closedByUs) noteDeath(it) }
        live = null
        if (degraded || closed) { mode = Mode.EXEC; return null }
        if (unsupported) { mode = Mode.EXEC; return null }
        val recent = synchronized(deaths) {
            val t = now()
            while (deaths.isNotEmpty() && t - deaths.first() > config.restartWindowMs) deaths.removeFirst()
            deaths.size
        }
        if (recent > 0) {
            val backoff = (config.backoffBaseMs shl (recent - 1).coerceAtMost(20)).coerceAtMost(config.backoffMaxMs)
            delay(backoff)
        }
        val l = open(Mode.SCRIPT) ?: if (unsupported) null else open(Mode.SHELL)
        if (l == null) {
            // Neither mode works (no xdotool, broken stream): stop retrying until the desktop restarts.
            if (!unsupported) degraded = true
            mode = Mode.EXEC
            return null
        }
        mode = l.mode
        live = l
        l
    }

    @Volatile private var unsupported = false

    private suspend fun open(m: Mode): Live? {
        val cmd = if (m == Mode.SCRIPT) "exec xdotool -" else "exec /bin/sh"
        val stream = try {
            bridge.openDesktopStream(cmd)
        } catch (e: UnsupportedOperationException) {
            unsupported = true
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("xdotool session: open failed: ${e.message}")
            return null
        }
        streamsOpened++
        val markers = Channel<String>(Channel.UNLIMITED)
        val live = Live(stream, m, markers)
        val outJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                stream.stdout.collect { if (isMarker(m, it)) markers.send(it) }
            } finally {
                markers.close()
            }
        }
        val errJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            stream.stderr.collect { line -> synchronized(live.stderr) { live.stderr.add(line) } }
        }
        live.jobs = listOf(outJob, errJob)
        val probe = if (m == Mode.SCRIPT) "getmouselocation\n" else "command -v xdotool >/dev/null 2>&1; ${marker(m)}\n"
        val ok = try {
            stream.write(probe)
            withTimeoutOrNull(config.probeTimeoutMs) { markers.receive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val good = ok != null && (m == Mode.SCRIPT || ok.removePrefix(SHELL_MARKER).trim() == "0")
        if (!good) {
            log("xdotool session: $m probe failed (${ok ?: "no answer"}; exit=${stream.exitCode.value})")
            live.closedByUs = true
            runCatching { stream.close("desktop-input-probe-failed") }
            outJob.cancel(); errJob.cancel()
            return null
        }
        live.clearStderr()
        log("xdotool session: started in $m mode")
        return live
    }

    companion object {
        const val XDOTOOL = "xdotool"
        const val SHELL_MARKER = "@@cos-sync "
        private val SCRIPT_MARKER = Regex("^x:-?\\d+ y:-?\\d+ screen:\\d+ window:\\d+\\s*$")

        internal fun marker(m: Mode) = if (m == Mode.SCRIPT) "getmouselocation" else "echo \"$SHELL_MARKER$?\""
        internal fun isMarker(m: Mode, line: String) =
            if (m == Mode.SCRIPT) SCRIPT_MARKER.matches(line) else line.startsWith(SHELL_MARKER)

        private val sessions = WeakHashMap<DesktopBridge, XdotoolSession>()
        private val appScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

        /** The process-wide session for [bridge] (Desktop tab and cos_gui share it). */
        fun shared(bridge: DesktopBridge): XdotoolSession = synchronized(sessions) {
            sessions.getOrPut(bridge) { XdotoolSession(bridge, appScope) }
        }
    }
}
