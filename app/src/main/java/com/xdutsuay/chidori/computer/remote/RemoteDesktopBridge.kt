package com.xdutsuay.chidori.computer.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xdutsuay.chidori.computer.desktop.CosExecResult
import com.xdutsuay.chidori.computer.desktop.DesktopBridge
import com.xdutsuay.chidori.computer.desktop.DesktopScreenshots
import com.xdutsuay.chidori.computer.desktop.DesktopState
import com.xdutsuay.chidori.computer.desktop.ImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Duration

/** [DesktopBridge] on a user VM: drives the `cindi-desktop` helper over pinned-key SSH. */
class RemoteDesktopBridge(
    val computer: RemoteComputer,
    private val identity: () -> SshIdentity,
    private val transport: SshTransport = JschTransport(),
) : DesktopBridge {

    private val _state = MutableStateFlow<DesktopState>(DesktopState.Stopped)
    override val state: StateFlow<DesktopState> = _state

    override suspend fun start(): Result<DesktopState.Running> {
        (_state.value as? DesktopState.Running)?.let { return Result.success(it) }
        _state.value = DesktopState.Starting
        val r = run(RemoteDesktopProtocol.startCommand(), START_TIMEOUT_MS)
        val display = if (r.exitCode == 0) RemoteDesktopProtocol.parseStart(r.stdout) else null
        if (display == null) {
            val msg = r.stderr.ifBlank { r.stdout }.trim().ifBlank { "start failed (exit ${r.exitCode})" }
            _state.value = DesktopState.Error(msg, null)
            return Result.failure(IllegalStateException(msg))
        }
        val running = DesktopState.Running(
            display = display.display,
            width = display.width,
            height = display.height,
            depth = display.depth,
            xauthorityGuestPath = "",
            framebufferHostFile = File(NO_LOCAL_FRAMEBUFFER),
            vncSocketHostPath = null,
        )
        _state.value = running
        return Result.success(running)
    }

    override suspend fun stop() {
        _state.value = DesktopState.Stopping
        run(RemoteDesktopProtocol.stopCommand(), STOP_TIMEOUT_MS)
        _state.value = DesktopState.Stopped
    }

    override suspend fun execInDesktop(cmd: String, timeout: Duration): CosExecResult {
        check(isDesktopRunning()) { "desktop not running" }
        return run(RemoteDesktopProtocol.execCommand(cmd), timeout.inWholeMilliseconds.toInt() + SSH_SLACK_MS)
    }

    override suspend fun screenshot(format: ImageFormat, scale: Float): ByteArray {
        check(isDesktopRunning()) { "desktop not running" }
        val r = run(RemoteDesktopProtocol.shotCommand(), SHOT_TIMEOUT_MS)
        val png = (if (r.exitCode == 0) RemoteDesktopProtocol.decodeShot(r.stdout) else null)
            ?: error(r.stderr.trim().ifBlank { "screenshot failed (exit ${r.exitCode})" })
        if (format == ImageFormat.PNG && scale >= 1f) return png
        return reencode(png, format, scale)
    }

    private suspend fun run(command: String, timeoutMs: Int): CosExecResult = withContext(Dispatchers.IO) {
        RemoteDesktopProtocol.toExecResult(transport.exec(computer, identity(), command, timeoutMs))
    }

    private fun reencode(png: ByteArray, format: ImageFormat, scale: Float): ByteArray {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("screenshot is not a PNG")
        val (w, h) = DesktopScreenshots.scaledSize(src.width, src.height, scale)
        val dst = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        try {
            return ByteArrayOutputStream().use { out ->
                val ok = when (format) {
                    ImageFormat.PNG -> dst.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ImageFormat.JPEG -> dst.compress(Bitmap.CompressFormat.JPEG, DesktopScreenshots.JPEG_QUALITY, out)
                }
                check(ok) { "bitmap compress failed" }
                out.toByteArray()
            }
        } finally {
            if (dst !== src) dst.recycle()
            src.recycle()
        }
    }

    private companion object {
        const val START_TIMEOUT_MS = 60_000
        const val STOP_TIMEOUT_MS = 30_000
        const val SHOT_TIMEOUT_MS = 30_000
        const val SSH_SLACK_MS = 5_000
        const val NO_LOCAL_FRAMEBUFFER = "/dev/null"
    }
}
