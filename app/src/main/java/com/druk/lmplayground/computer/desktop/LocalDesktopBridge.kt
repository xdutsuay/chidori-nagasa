package com.druk.lmplayground.computer.desktop

import android.content.Context
import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.computer.LocalComputerRuntime
import com.druk.lmplayground.computer.ProotCommand
import com.druk.lmplayground.computer.ProotTerminator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Real [DesktopBridge] (contract in DesktopBridge.kt, shared with the chat tools): installs the pinned package set with `apk` inside the COS rootfs
 * and runs the session through [DesktopSession] (one proot tree, framebuffer screenshots,
 * no VNC). One instance per process ([get]); the session outlives the Computer screen and
 * is stopped when the Computer is disabled or [stop] is called. [App] publishes the
 * instance as [DesktopBridges.bridge] at startup.
 *
 * Beyond the contract: [install] (not part of [DesktopBridge]), [installMessage] (the
 * current apk line while [DesktopState.Installing]), [isInstalled], [refresh], [stopAsync]
 * and [frame] (decoded framebuffer for JVM tests and the box smoke run).
 */
class LocalDesktopBridge internal constructor(
    private val runtime: LocalComputerRuntime,
    val config: DesktopConfig = DesktopConfig.DEFAULT,
    private val log: (String) -> Unit = { msg -> runCatching { android.util.Log.i(LocalComputerRuntime.LOG_TAG, msg) } },
    private val installTimeoutMs: Long = INSTALL_TIMEOUT_MS,
    private val readyTimeoutMs: Long = DesktopSession.READY_TIMEOUT_MS,
) : DesktopBridge {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(idleState())
    override val state: StateFlow<DesktopState> = _state.asStateFlow()

    private val _installMessage = MutableStateFlow<String?>(null)

    /** Current apk progress line while installing (e.g. "Installing xvfb (21.1.25-r0)"). */
    val installMessage: StateFlow<String?> = _installMessage.asStateFlow()

    @Volatile private var session: DesktopSession? = null
    private val openStreams = CopyOnWriteArrayList<ProotDesktopStream>()

    private val stateDir: File get() = File(runtime.hostRootfsDir, DesktopCommand.GUEST_STATE_DIR.trimStart('/'))
    private val installedMarker: File get() = File(runtime.hostRootfsDir, DesktopCommand.GUEST_INSTALLED_MARKER.trimStart('/'))
    internal val fbFile: File get() = File(runtime.hostRootfsDir, DesktopCommand.GUEST_FB_FILE.trimStart('/'))

    private fun move(e: DesktopStateMachine.Event): DesktopState {
        val next = DesktopStateMachine.next(_state.value, e)
        if (next != null) _state.value = next
        return _state.value
    }

    /** Marker matches the current default pins for this arch (and the rootfs is there). */
    fun isInstalled(): Boolean {
        val arch = runtime.alpineArch() ?: return false
        if (!runtime.rootfsPresent()) return false
        val text = runCatching { installedMarker.readText() }.getOrNull() ?: return false
        val want = DesktopPackages.defaultSet(arch)?.let { markerLine(it) } ?: return false
        return text.lineSequence().any { it.trim() == want }
    }

    private fun markerLine(set: DesktopPackages.PinnedSet) = "${set.id} ${set.arch} ${set.apkAddArgs.joinToString(" ")}"

    private fun idleState(): DesktopState = if (isInstalled()) DesktopState.Stopped else DesktopState.NotInstalled

    /** Re-reads the install marker when idle (e.g. after a rootfs reinstall). */
    fun refresh() {
        val s = _state.value
        if (s == DesktopState.NotInstalled || s == DesktopState.Stopped || s is DesktopState.Error) {
            val idle = idleState()
            if (idle == DesktopState.NotInstalled) move(DesktopStateMachine.Event.Uninstalled)
            else if (s == DesktopState.NotInstalled) _state.value = DesktopState.Stopped
        }
    }

    /** Installs (or verifies) the pinned set; idempotent. Returns the resulting state. */
    suspend fun install(withFirefox: Boolean = false): DesktopState = mutex.withLock {
        withContext(Dispatchers.IO) { installLocked(withFirefox) }
    }

    private suspend fun installLocked(withFirefox: Boolean): DesktopState {
        if (!DesktopStateMachine.canInstall(_state.value)) return _state.value
        move(DesktopStateMachine.Event.InstallStarted)
        val arch = runtime.alpineArch()
            ?: return installFail("unsupported CPU for the desktop")
        val sets = DesktopPackages.setsFor(arch, withFirefox)
        if (sets.isEmpty()) return installFail("no pinned desktop set for $arch")
        val pins = sets.fold(LinkedHashMap<String, String>()) { m, s -> m.apply { putAll(s.pins) } }
        log(
            "desktop install sets=${sets.joinToString(",") { it.id }} arch=$arch " +
                "download=${DesktopPackages.formatMiB(DesktopPackages.downloadBytes(sets))} " +
                "installed=${DesktopPackages.formatMiB(DesktopPackages.installedBytes(sets))}",
        )
        if (verify(pins)?.ok == true) {
            log("desktop install: pinned packages already present; verified")
            return installDone(sets)
        }
        _installMessage.value = "Downloading package index"
        move(DesktopStateMachine.Event.InstallProgress(0f))
        val cmd = "apk add -U " + sets.flatMap { it.apkAddArgs }.joinToString(" ") { ShellQuote.quote(it) }
        val r = runtime.execStreaming(
            ProotCommand.guestShellArgv(cmd),
            cwdRelative = "root",
            cancelReason = "desktop-install",
            timeoutMs = installTimeoutMs,
        ) { line ->
            DesktopPackages.parseProgress(line)?.let { (i, n) ->
                _installMessage.value = line.substringAfter(')').trim()
                move(DesktopStateMachine.Event.InstallProgress(i.toFloat() / n))
            }
        }
        log(DesktopPackages.indexSnapshotReport(File(runtime.hostRootfsDir, "var/cache/apk"), arch))
        if (r.exitCode != 0) {
            val tail = (r.stderr.ifBlank { r.stdout }).lineSequence().filter { it.isNotBlank() }.toList().takeLast(3)
                .joinToString(" | ")
            return installFail("apk add failed (exit ${r.exitCode}): $tail" + DesktopPackages.failureHint(r.stderr + r.stdout))
        }
        val v = verify(pins) ?: return installFail("could not list installed packages")
        if (!v.ok) return installFail("installed versions differ from pins: ${v.describe(pins)}")
        return installDone(sets)
    }

    /** `apk info -v` vs pins; null if apk could not run. */
    private suspend fun verify(pins: Map<String, String>): DesktopPackages.Verification? {
        val r = runtime.execStreaming(listOf("/sbin/apk", "info", "-v"), "root", "desktop-verify") { }
        if (r.exitCode != 0) return null
        return DesktopPackages.verify(r.stdout.lines(), pins)
    }

    private fun installDone(sets: List<DesktopPackages.PinnedSet>): DesktopState {
        stateDir.mkdirs()
        val previous = runCatching { installedMarker.readLines() }.getOrDefault(emptyList())
        val lines = (previous + sets.map { markerLine(it) }).filter { it.isNotBlank() }.distinct()
        installedMarker.writeText(lines.joinToString("\n") + "\n")
        log("desktop install ok sets=${sets.joinToString(",") { it.id }}")
        _installMessage.value = null
        return move(DesktopStateMachine.Event.InstallSucceeded)
    }

    private fun installFail(msg: String): DesktopState {
        log("desktop install failed: $msg")
        _installMessage.value = null
        return move(DesktopStateMachine.Event.InstallFailed(msg))
    }

    /** Idempotent: returns the current [DesktopState.Running] if the session is up. */
    override suspend fun start(): Result<DesktopState.Running> = mutex.withLock {
        withContext(Dispatchers.IO) { startLocked() }
    }

    private fun startLocked(): Result<DesktopState.Running> {
        (_state.value as? DesktopState.Running)?.let { if (session?.isAlive == true) return Result.success(it) }
        if (!isInstalled()) {
            refresh()
            return Result.failure(IllegalStateException("desktop not installed — Install desktop on the Computer screen"))
        }
        if (DesktopStateMachine.next(_state.value, DesktopStateMachine.Event.StartRequested) == null) {
            return Result.failure(IllegalStateException("cannot start desktop while ${DesktopStateMachine.label(_state.value)}"))
        }
        move(DesktopStateMachine.Event.StartRequested)
        val s = DesktopSession(
            runtime = runtime,
            config = config,
            log = log,
            readyTimeoutMs = readyTimeoutMs,
            onUnexpectedExit = { msg ->
                session = null
                move(DesktopStateMachine.Event.Exited(msg))
            },
        )
        return when (val o = s.start()) {
            is DesktopSession.Outcome.Ready -> {
                session = s
                val running = DesktopState.Running(
                    display = o.ready.display,
                    width = o.ready.width,
                    height = o.ready.height,
                    depth = o.ready.depth,
                    xauthorityGuestPath = DesktopCommand.GUEST_XAUTHORITY,
                    framebufferHostFile = fbFile,
                    vncSocketHostPath = null,
                )
                move(DesktopStateMachine.Event.Ready(running))
                Result.success(running)
            }
            is DesktopSession.Outcome.Failed -> {
                move(DesktopStateMachine.Event.StartFailed(o.message))
                Result.failure(IllegalStateException(o.message))
            }
        }
    }

    override suspend fun stop() {
        mutex.withLock { withContext(Dispatchers.IO) { stopLocked("desktop-stop") } }
    }

    private fun stopLocked(reason: String): DesktopState {
        openStreams.toList().forEach { runCatching { it.closeBlocking(reason) } }
        openStreams.clear()
        val s = session
        session = null
        if (s == null) {
            if (_state.value is DesktopState.Running) move(DesktopStateMachine.Event.Exited("session lost"))
            return _state.value
        }
        move(DesktopStateMachine.Event.StopRequested)
        s.stop(reason)
        return move(DesktopStateMachine.Event.Stopped)
    }

    /** Fire-and-forget stop (Computer disabled, app teardown). */
    fun stopAsync(reason: String = "desktop-stop") {
        scope.launch { mutex.withLock { stopLocked(reason) } }
    }

    /**
     * Runs the already-quoted [cmd] with `sh -c` (DISPLAY, XAUTHORITY, HOME exported) through
     * the CP1 [LocalComputerRuntime.execStreaming] path, so coroutine cancellation (chat Stop)
     * tears the proot tree down via ProotTerminator exactly like cos_exec.
     */
    override suspend fun execInDesktop(cmd: String, timeout: Duration): ComputerExecResult {
        if (!isDesktopRunning() || session?.isAlive != true) {
            return ComputerExecResult(127, "", "desktop not running — start it on the Computer screen")
        }
        return runtime.execStreaming(
            DesktopCommand.execArgv(config, cmd),
            cwdRelative = "root",
            cancelReason = "desktop-exec",
            timeoutMs = timeout.inWholeMilliseconds.coerceAtLeast(1),
        ) { }
    }

    /** XWD from the Xvfb `-fbdir` file → PNG/JPEG via the shared [DesktopScreenshots]. */
    override suspend fun screenshot(format: ImageFormat, scale: Float): ByteArray = withContext(Dispatchers.IO) {
        val running = _state.value as? DesktopState.Running ?: throw IllegalStateException("desktop not running")
        DesktopScreenshots.encode(running.framebufferHostFile, format, scale)
    }

    /**
     * Long-lived `sh -c` with DISPLAY/XAUTHORITY/HOME (stdin open) for [XdotoolSession].
     * Torn down via [ProotTerminator] on [DesktopStream.close] or desktop [stop].
     */
    override suspend fun openDesktopStream(cmd: String): DesktopStream = withContext(Dispatchers.IO) {
        if (!isDesktopRunning() || session?.isAlive != true) {
            throw IllegalStateException("desktop not running — start it on the Computer screen")
        }
        val stdoutLines = CopyOnWriteArrayList<(String) -> Unit>()
        val stderrLines = CopyOnWriteArrayList<(String) -> Unit>()
        val handle = runtime.launchSupervisedStream(
            guestArgv = DesktopCommand.execArgv(config, cmd),
            noSeccomp = false,
            onStdout = { line -> stdoutLines.forEach { it(line) } },
            onStderr = { line -> stderrLines.forEach { it(line) } },
        )
        ProotDesktopStream(runtime, handle, stdoutLines, stderrLines).also { stream ->
            openStreams.add(stream)
            stream.onClosed = { openStreams.remove(stream) }
        }
    }

    private class ProotDesktopStream(
        private val runtime: LocalComputerRuntime,
        private val handle: ProotTerminator.Handle,
        private val stdoutListeners: CopyOnWriteArrayList<(String) -> Unit>,
        private val stderrListeners: CopyOnWriteArrayList<(String) -> Unit>,
    ) : DesktopStream {
        private val closed = AtomicBoolean(false)
        private val _exit = MutableStateFlow<Int?>(null)
        override val exitCode: StateFlow<Int?> = _exit.asStateFlow()
        var onClosed: (() -> Unit)? = null

        private val writer = BufferedWriter(
            OutputStreamWriter(handle.proc.outputStream, StandardCharsets.UTF_8),
            8 * 1024,
        )

        override val stdout: Flow<String> = callbackFlow {
            val listener: (String) -> Unit = { trySend(it) }
            stdoutListeners.add(listener)
            awaitClose { stdoutListeners.remove(listener) }
        }

        override val stderr: Flow<String> = callbackFlow {
            val listener: (String) -> Unit = { trySend(it) }
            stderrListeners.add(listener)
            awaitClose { stderrListeners.remove(listener) }
        }

        init {
            Thread({
                val code = runCatching { handle.proc.waitFor() }.getOrDefault(-1)
                _exit.value = code
            }, "cos-desktop-stream-wait").apply { isDaemon = true }.start()
        }

        override suspend fun write(data: String) = withContext(Dispatchers.IO) {
            if (closed.get()) throw IOException("desktop stream closed")
            synchronized(writer) {
                writer.write(data)
                writer.flush()
            }
        }

        override suspend fun close(reason: String) = withContext(Dispatchers.IO) {
            closeBlocking(reason)
        }

        fun closeBlocking(reason: String) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { synchronized(writer) { writer.close() } }
            runtime.terminateSupervised(handle, reason)
            if (_exit.value == null) {
                _exit.value = runCatching { handle.proc.exitValue() }.getOrDefault(130)
            }
            onClosed?.invoke()
        }
    }

    /** Alpine arch of the rootfs (`aarch64` / `x86_64`), or null when it is not set up. */
    fun arch(): String? = runtime.alpineArch()

    /** Decoded current frame (no Android Bitmap; JVM tests / smoke run), or null if not running. */
    fun frame(): XwdDecoder.Image? {
        val running = _state.value as? DesktopState.Running ?: return null
        return XwdDecoder.decode(running.framebufferHostFile)
    }

    companion object {
        /** Long-running `apk add` (~113 MiB on slow mobile links). */
        const val INSTALL_TIMEOUT_MS = 30L * 60 * 1000

        @Volatile private var instance: LocalDesktopBridge? = null

        /** Process-wide bridge (display [DesktopConfig.DEFAULT]); also published as [DesktopBridges.bridge]. */
        fun get(context: Context): LocalDesktopBridge =
            instance ?: synchronized(this) {
                instance ?: LocalDesktopBridge(LocalComputerRuntime.create(context.applicationContext)).also {
                    instance = it
                    DesktopBridges.bridge = it
                }
            }

        /** Existing instance without creating one (e.g. to stop it on disable). */
        fun peek(): LocalDesktopBridge? = instance
    }
}
