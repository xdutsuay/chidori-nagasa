package com.xdutsuay.chidori.computer.desktop.share

import com.xdutsuay.chidori.computer.ComputerExecResult
import com.xdutsuay.chidori.computer.desktop.DesktopBridge
import com.xdutsuay.chidori.computer.desktop.DesktopCapability
import com.xdutsuay.chidori.computer.desktop.DesktopState
import com.xdutsuay.chidori.computer.desktop.DesktopStream
import com.xdutsuay.chidori.computer.desktop.ImageFormat
import com.xdutsuay.chidori.computer.desktop.ViewerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * Scriptable [DesktopStream] for [VncShare] tests: tests push stdout/stderr lines and
 * exit codes; [close] mimics the real idempotent contract (only the first close while
 * alive is recorded and acted on).
 */
class FakeViewerStream(val cmd: String) : DesktopStream {
    private val out = Channel<String>(Channel.UNLIMITED)
    private val err = Channel<String>(Channel.UNLIMITED)
    override val stdout: Flow<String> = out.receiveAsFlow()
    override val stderr: Flow<String> = err.receiveAsFlow()
    override val exitCode = MutableStateFlow<Int?>(null)

    /** Reasons recorded for closes that had an effect (close is idempotent). */
    val closeReasons = CopyOnWriteArrayList<String>()

    fun emitOut(line: String) { out.trySend(line) }
    fun emitErr(line: String) { err.trySend(line) }

    /** Kills the process on its own (not closed by [close]). */
    fun die(code: Int) {
        if (exitCode.value != null) return
        exitCode.value = code
        out.close(); err.close()
    }

    override suspend fun write(data: String) {
        if (exitCode.value != null) throw java.io.IOException("stream closed")
    }

    override suspend fun close(reason: String) {
        if (exitCode.value != null) return
        closeReasons += reason
        die(130)
    }
}

/**
 * Scriptable [DesktopBridge] for [VncShare] tests: records [execInDesktop] commands and
 * timeouts, hands out [FakeViewerStream]s from [openDesktopStream], and lets tests queue
 * per-call exit codes. Runs happily on kotlinx-coroutines-test virtual time.
 */
class FakeViewerBridge(
    display: String = ":1",
    xauthorityGuestPath: String = "/root/.Xauthority",
) : DesktopBridge {
    override val state = MutableStateFlow<DesktopState>(
        DesktopState.Running(
            display, 1280, 720, 24, xauthorityGuestPath,
            File("/nonexistent/Xvfb_screen0"), null,
        ),
    )
    override val viewer = MutableStateFlow<ViewerState>(ViewerState.Off)

    override fun capabilities(): Set<DesktopCapability> =
        setOf(DesktopCapability.EXEC, DesktopCapability.SCREENSHOT, DesktopCapability.VIEWER)

    val execCommands = CopyOnWriteArrayList<String>()
    val execTimeoutsMs = CopyOnWriteArrayList<Long>()
    val openedCmds = CopyOnWriteArrayList<String>()
    val streams = CopyOnWriteArrayList<FakeViewerStream>()

    /** Queue of exit codes consumed by successive [execInDesktop] calls; 0 when empty. */
    val execExitCodes = ConcurrentLinkedQueue<Int>()

    /** Called right after a stream is opened, before any collector has run (channel buffers). */
    var onOpen: (FakeViewerStream) -> Unit = {}

    /** When true, [execInDesktop] suspends until [execRelease] completes. */
    var hangExec = false
    val execEntered = CompletableDeferred<Unit>()
    val execRelease = CompletableDeferred<Unit>()

    override suspend fun execInDesktop(cmd: String, timeout: Duration): ComputerExecResult {
        execCommands += cmd
        execTimeoutsMs += timeout.inWholeMilliseconds
        if (hangExec) {
            execEntered.complete(Unit)
            execRelease.await()
        }
        return ComputerExecResult(exitCode = execExitCodes.poll() ?: 0, stdout = "", stderr = "")
    }

    override suspend fun openDesktopStream(cmd: String): DesktopStream {
        openedCmds += cmd
        return FakeViewerStream(cmd).also { stream ->
            streams += stream
            onOpen(stream)
        }
    }

    override suspend fun start(): Result<DesktopState.Running> =
        Result.success(state.value as DesktopState.Running)

    override suspend fun stop() {
        state.value = DesktopState.Stopped
    }

    override suspend fun screenshot(format: ImageFormat, scale: Float): ByteArray = ByteArray(0)
}
