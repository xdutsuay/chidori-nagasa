package com.druk.lmplayground.computer.desktop

import com.druk.lmplayground.computer.ComputerExecResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
 * DesktopBridge contract, copied verbatim from Dr gero's CP2 spike
 * (research/cp2-desktop-spike.md, section 5 "DesktopBridge contract").
 *
 * RECONCILIATION NOTE: the desktop runtime (branch cp/cos-desktop-runtime) had
 * not committed its bridge when the chat tools were written, so this shared file
 * defines it. When the runtime lands, keep ONE copy of these declarations (this
 * file or the runtime's) and delete the other; the chat tools only depend on
 * [DesktopBridge.state], [DesktopBridge.isDesktopRunning],
 * [DesktopBridge.execInDesktop] and [DesktopBridge.screenshot].
 *
 * Only additions beyond the doc: [CosExecResult] (the doc's name for the
 * `cos_exec` result type, aliased to the CP1 [ComputerExecResult]) and
 * [ImageFormat] (referenced by the doc, not spelled out there).
 */

/** Same result type as `cos_exec` (CP1 runtime). */
typealias CosExecResult = ComputerExecResult

enum class ImageFormat { PNG, JPEG }

sealed interface DesktopState {
    data object NotInstalled : DesktopState
    data class Installing(val progress: Float?) : DesktopState
    data object Stopped : DesktopState
    data object Starting : DesktopState
    data class Running(
        val display: String,          // ":1"
        val width: Int, val height: Int, val depth: Int,  // from launch args, verified via xdpyinfo
        val xauthorityGuestPath: String,  // "/root/.Xauthority"
        val framebufferHostFile: File,    // <rootfs>/tmp/cos-fb/Xvfb_screen0 (XWD)
        val vncSocketHostPath: String?,   // null unless RFB viewer enabled
    ) : DesktopState
    data object Stopping : DesktopState
    data class Error(val message: String, val cause: Throwable?) : DesktopState
}

interface DesktopBridge {
    val state: StateFlow<DesktopState>
    fun isDesktopRunning(): Boolean = state.value is DesktopState.Running
    suspend fun start(): Result<DesktopState.Running>   // idempotent
    suspend fun stop()
    /** Runs cmd via CP1 runtime/ProotTerminator with DISPLAY, XAUTHORITY, HOME set; same result type as cos_exec. */
    suspend fun execInDesktop(cmd: String, timeout: Duration = 30.seconds): CosExecResult
    /** Reads the Xvfb -fbdir XWD file and returns an ARGB bitmap / PNG bytes; no guest process, no imagemagick. */
    suspend fun screenshot(format: ImageFormat = ImageFormat.PNG, scale: Float = 1f): ByteArray

    /**
     * CP2 input-layer ADDITION (proposed by the chat-tools/input branch, see
     * docs/COS_DESKTOP_INPUT.md; not in the spike doc yet): starts [cmd] with `sh -c`
     * in the desktop environment (same DISPLAY/XAUTHORITY/HOME as [execInDesktop])
     * through the CP1 runtime, keeps its stdin open and returns at once.
     *
     * Requirements for the runtime:
     * - one proot per call, registered with ProotTerminator; [DesktopStream.close]
     *   and desktop [stop] tear it down via ProotTerminator (SIGQUIT, grace, SIGKILL)
     *   with the given reason, exactly like a cancelled `cos_exec`;
     * - no exec timeout (the stream is long-lived); stdout/stderr are delivered
     *   line by line as they arrive (no output cap beyond a sane line length);
     * - throws (e.g. IllegalStateException) if the desktop is not running.
     *
     * The default throws [UnsupportedOperationException]: callers (XdotoolSession)
     * then fall back to one [execInDesktop] per command.
     */
    suspend fun openDesktopStream(cmd: String): DesktopStream =
        throw UnsupportedOperationException("openDesktopStream not implemented by this DesktopBridge")

    /** What this computer supports beyond the basics. */
    fun capabilities(): Set<DesktopCapability> = setOf(DesktopCapability.EXEC, DesktopCapability.SCREENSHOT)

    /** Human VNC viewer sharing state (see [com.druk.lmplayground.computer.desktop.share.VncShare]). */
    val viewer: StateFlow<ViewerState> get() = ViewerState.OFF_FLOW

    suspend fun startViewer(
        lease: ShareLease = ShareLease(),
        scope: ViewerScope = ViewerScope.LOOPBACK,
    ): Result<ViewerEndpoint> =
        Result.failure(UnsupportedOperationException("viewer not supported by this DesktopBridge"))

    suspend fun stopViewer(reason: String = com.druk.lmplayground.computer.desktop.share.VncShare.STOP_REASON) {}
}

enum class DesktopCapability { EXEC, SCREENSHOT, INPUT_STREAM, VIEWER }

/** LOOPBACK: 127.0.0.1 only (reach it with `adb forward`). LAN is not available yet. */
enum class ViewerScope { LOOPBACK, LAN }

data class ShareLease(val maxMs: Long = DEFAULT_MAX_MS) {
    companion object {
        const val DEFAULT_MAX_MS = 30L * 60 * 1000
    }
}

data class ViewerEndpoint(val host: String, val port: Int, val password: String, val expiresAtMs: Long)

sealed interface ViewerState {
    data object Off : ViewerState
    data object Starting : ViewerState
    data class Shared(val endpoint: ViewerEndpoint) : ViewerState
    data class Error(val message: String) : ViewerState

    companion object {
        val OFF_FLOW: StateFlow<ViewerState> = kotlinx.coroutines.flow.MutableStateFlow<ViewerState>(Off)
    }
}

/**
 * A long-lived process in the desktop environment (see [DesktopBridge.openDesktopStream]).
 * All members are thread-safe.
 */
interface DesktopStream {
    /** Writes [data] (UTF-8) to stdin and flushes. Throws [java.io.IOException] once the process is gone. */
    suspend fun write(data: String)

    /** stdout lines without the trailing newline; hot, single collector; completes when the process exits. */
    val stdout: Flow<String>

    /** stderr lines, same rules as [stdout]. */
    val stderr: Flow<String>

    /** null while running, then the exit code (130 when terminated by [close] or chat Stop). */
    val exitCode: StateFlow<Int?>

    /** Closes stdin and terminates the process through ProotTerminator with [reason]. Idempotent. */
    suspend fun close(reason: String = CLOSE_REASON)

    companion object {
        const val CLOSE_REASON = "desktop-input-close"
    }
}

/**
 * **Single process-wide source of truth** for COS desktop [DesktopState]: the Computer
 * screen panel, [DesktopViewer], chat tools (`cos_screenshot` / `cos_gui`), and
 * [com.druk.lmplayground.computer.CosPromptNote] live-desktop notes all read
 * [bridge].state (set once via [LocalDesktopBridge.get] at app start). Null means
 * desktop tools are unavailable (hidden from the model; "desktop not running" if called).
 */
object DesktopBridges {
    @Volatile var bridge: DesktopBridge? = null
}
