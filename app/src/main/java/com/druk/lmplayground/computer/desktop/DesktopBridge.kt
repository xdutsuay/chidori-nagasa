package com.druk.lmplayground.computer.desktop

import com.druk.lmplayground.computer.ComputerExecResult
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
}

/**
 * Process-wide handle to the desktop runtime's [DesktopBridge], for the chat
 * tools (which are created per conversation by `ToolRegistry.createDefault`).
 * The desktop runtime sets [bridge] once at startup (e.g. in `App.onCreate` or
 * its service); while it is null the desktop tools are simply unavailable
 * (hidden from the model, "desktop not running" if called).
 */
object DesktopBridges {
    @Volatile var bridge: DesktopBridge? = null
}
