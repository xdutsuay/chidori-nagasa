package com.xdutsuay.chidori.computer.desktop

import com.xdutsuay.chidori.computer.ComputerExecResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration

/**
 * The viewer additions to [DesktopBridge] must carry defaults so every existing
 * implementation keeps compiling: [DesktopBridge.capabilities], [DesktopBridge.viewer],
 * [DesktopBridge.startViewer], [DesktopBridge.stopViewer] (and the [ShareLease]
 * default max lease). Verified here with a minimal bridge that implements only the
 * pre-viewer abstract members.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBridgeViewerDefaultsTest {

    private class MinimalBridge : DesktopBridge {
        override val state = MutableStateFlow<DesktopState>(DesktopState.Stopped)
        override suspend fun start(): Result<DesktopState.Running> =
            Result.success(DesktopState.Running(":0", 1, 1, 1, "/", File("/tmp/x"), null))
        override suspend fun stop() {}
        override suspend fun execInDesktop(cmd: String, timeout: Duration): ComputerExecResult =
            ComputerExecResult(exitCode = 0, stdout = "", stderr = "")
        override suspend fun screenshot(format: ImageFormat, scale: Float): ByteArray = ByteArray(0)
    }

    @Test
    fun `default capabilities are exec plus screenshot`() {
        assertEquals(setOf(DesktopCapability.EXEC, DesktopCapability.SCREENSHOT), MinimalBridge().capabilities())
    }

    @Test
    fun `default viewer flow is Off`() = runTest {
        assertEquals(ViewerState.Off, MinimalBridge().viewer.value)
    }

    @Test
    fun `default startViewer fails with UnsupportedOperationException`() = runTest {
        val r = MinimalBridge().startViewer()
        assertFalse(r.isSuccess)
        assertTrue(r.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test
    fun `default stopViewer is a no-op`() = runTest {
        // Must not throw for either the default reason or a custom one.
        MinimalBridge().stopViewer()
        MinimalBridge().stopViewer("custom-reason")
    }

    @Test
    fun `default share lease is 30 minutes`() {
        assertEquals(1_800_000L, ShareLease.DEFAULT_MAX_MS)
        assertEquals(1_800_000L, ShareLease().maxMs)
    }
}
