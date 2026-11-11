package com.xdutsuay.chidori.tools

import com.xdutsuay.chidori.computer.ComputerExecResult
import com.xdutsuay.chidori.computer.desktop.CosExecResult
import com.xdutsuay.chidori.computer.desktop.DesktopBridge
import com.xdutsuay.chidori.computer.desktop.DesktopState
import com.xdutsuay.chidori.computer.desktop.ImageFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import kotlin.time.Duration

/** In-memory [DesktopBridge] for tool tests: records commands, can block until cancelled. */
class FakeDesktopBridge(width: Int = 1280, height: Int = 720, display: String = ":7") : DesktopBridge {
    override val state = MutableStateFlow<DesktopState>(
        DesktopState.Running(display, width, height, 24, "/root/.Xauthority", File("/nonexistent/Xvfb_screen0"), null),
    )
    val commands = CopyOnWriteArrayList<String>()
    val screenshots = CopyOnWriteArrayList<Pair<ImageFormat, Float>>()
    var execResult = ComputerExecResult(0, "", "")
    var pngOverride: ((ImageFormat, Float) -> ByteArray)? = null

    /** When true, exec/screenshot suspend until cancelled (chat Stop). */
    @Volatile var hang = false
    val entered = CountDownLatch(1)
    @Volatile var cancelledWith: Throwable? = null

    fun stopDesktop() { state.value = DesktopState.Stopped }

    override suspend fun start(): Result<DesktopState.Running> = Result.success(state.value as DesktopState.Running)
    override suspend fun stop() = stopDesktop()

    private suspend fun maybeHang() {
        if (!hang) return
        entered.countDown()
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelledWith = e
            throw e
        }
    }

    override suspend fun execInDesktop(cmd: String, timeout: Duration): CosExecResult {
        commands += cmd
        maybeHang()
        return execResult
    }

    override suspend fun screenshot(format: ImageFormat, scale: Float): ByteArray {
        screenshots += format to scale
        maybeHang()
        pngOverride?.let { return it(format, scale) }
        val s = state.value as DesktopState.Running
        return pngHeader((s.width * scale).roundToInt(), (s.height * scale).roundToInt())
    }

    companion object {
        /** Signature + IHDR: all that [com.xdutsuay.chidori.computer.desktop.PngInfo] reads. */
        fun pngHeader(w: Int, h: Int, padTo: Int = 33): ByteArray {
            val b = ByteArray(maxOf(padTo, 24))
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
                .copyInto(b)
            fun be(o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }
            be(16, w); be(20, h)
            return b
        }
    }
}
