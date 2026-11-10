package com.druk.lmplayground.computer.desktop.share

import com.druk.lmplayground.computer.desktop.DesktopPackages
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.ShareLease
import com.druk.lmplayground.computer.desktop.ShellQuote
import com.druk.lmplayground.computer.desktop.ViewerEndpoint
import com.druk.lmplayground.computer.desktop.ViewerScope
import com.druk.lmplayground.computer.desktop.ViewerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 Computer sharing tests for [VncShare]: loopback-only VNC viewer over the
 * Alpine X desktop. All waits (readiness, lease) run on the runTest virtual clock via
 * [TestScope.backgroundScope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VncShareTest {

    private val bridge = FakeViewerBridge()
    private val password = "passw0rd"

    private fun TestScope.share(
        port: Int = VncShare.DEFAULT_PORT,
        pw: () -> String = { password },
        readyTimeoutMs: Long = VncShare.READY_TIMEOUT_MS,
        nowMs: () -> Long = { testScheduler.currentTime },
    ): VncShare = VncShare(bridge, backgroundScope, port, pw, readyTimeoutMs, nowMs)

    /**
     * Runs [VncShare.start] on [TestScope.backgroundScope] so the ready/lease waits are
     * driven by virtual time (a direct suspend call would block the test coroutine).
     */
    private fun TestScope.launchStart(
        s: VncShare,
        lease: ShareLease = ShareLease(),
        scope: ViewerScope = ViewerScope.LOOPBACK,
    ): CompletableDeferred<Result<ViewerEndpoint>> {
        val done = CompletableDeferred<Result<ViewerEndpoint>>()
        backgroundScope.launch { done.complete(s.start(lease, scope)) }
        return done
    }

    /** Drains the scheduler and requires [VncShare.start] to have finished. */
    private fun TestScope.awaitStart(done: CompletableDeferred<Result<ViewerEndpoint>>): Result<ViewerEndpoint> {
        runCurrent()
        assertTrue("start() did not complete", done.isCompleted)
        return done.getCompleted()
    }

    private fun assertErrorState(state: ViewerState, messageCheck: (String) -> Boolean) {
        assertTrue("expected ViewerState.Error but was $state", state is ViewerState.Error)
        val msg = (state as ViewerState.Error).message
        assertTrue("unexpected error message: $msg", messageCheck(msg))
    }

    // ---- rule 9 + rules 5/6/7 (happy path, x11vnc already installed) ----

    @Test
    fun `start shares a loopback endpoint with the exact x11vnc command`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        val endpoint = awaitStart(launchStart(s, ShareLease(maxMs = 45_000))).getOrThrow()

        assertEquals(ViewerEndpoint(VncShare.LOOPBACK_HOST, 5901, password, 45_000), endpoint)
        assertEquals(ViewerState.Shared(endpoint), s.state.value)

        // Rule 7: exact stream command, including -localhost (never reachable off-loopback)
        // and -noshm (Android/proot has no SysV shared memory; x11vnc would die with
        // "shmget(scanline) failed").
        assertEquals(
            "exec 'x11vnc' '-display' ':1' '-auth' '/root/.Xauthority' '-localhost' " +
                "'-rfbport' '5901' '-passwdfile' 'rm:/tmp/cos-vnc/passwd' '-forever' '-shared' " +
                "'-noxdamage' '-noshm'",
            bridge.openedCmds.single(),
        )
        assertTrue("stream command must contain -noshm", bridge.openedCmds.single().contains("-noshm"))

        // Rules 5+6: capability probe, then the password file; NO apk add when present.
        assertEquals(2, bridge.execCommands.size)
        assertEquals("command -v x11vnc", bridge.execCommands[0])
        val pwCmd = bridge.execCommands[1]
        assertTrue("password command must start with 'umask 077': $pwCmd", pwCmd.startsWith("umask 077"))
        assertTrue("password command must contain the quoted password", pwCmd.contains(ShellQuote.quote(password)))
        assertTrue("password command must contain ${VncShare.PASSWORD_FILE}", pwCmd.contains(VncShare.PASSWORD_FILE))
        assertTrue(bridge.execCommands.none { it.startsWith("apk add") })
    }

    @Test
    fun `ready line may arrive on stderr`() = runTest {
        bridge.onOpen = { it.emitErr("PORT=5901") }
        val s = share()
        assertEquals(5901, awaitStart(launchStart(s)).getOrThrow().port)
    }

    @Test
    fun `custom port is parsed from stdout and non-matching lines are skipped`() = runTest {
        bridge.onOpen = {
            it.emitOut("x11vnc: starting up")
            it.emitOut("PORT=5902")
        }
        val s = share(port = 5902)
        assertEquals(5902, awaitStart(launchStart(s)).getOrThrow().port)
        assertTrue(bridge.openedCmds.single().contains("'-rfbport' '5902'"))
    }

    // ---- rule 1 ----

    @Test
    fun `start while Shared returns the same endpoint without new exec or stream`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        val first = awaitStart(launchStart(s)).getOrThrow()
        val execCount = bridge.execCommands.size
        val streamCount = bridge.streams.size

        val second = s.start(ShareLease(maxMs = 1), ViewerScope.LOOPBACK)

        assertTrue(second.isSuccess)
        assertEquals(first, second.getOrThrow())
        assertEquals(execCount, bridge.execCommands.size)
        assertEquals(streamCount, bridge.streams.size)
        assertEquals(ViewerState.Shared(first), s.state.value)
    }

    // ---- rule 2 ----

    @Test
    fun `LAN scope is refused before any exec`() = runTest {
        val s = share()
        val r = s.start(scope = ViewerScope.LAN)

        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(ViewerState.Off, s.state.value)
        assertTrue(bridge.execCommands.isEmpty())
        assertTrue(bridge.streams.isEmpty())
    }

    // ---- rule 3 ----

    @Test
    fun `start fails with IllegalStateException when the desktop is not running`() = runTest {
        bridge.state.value = DesktopState.Stopped
        val s = share()
        val r = s.start()

        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
        assertEquals(ViewerState.Off, s.state.value)
        assertTrue(bridge.execCommands.isEmpty())
        assertTrue(bridge.streams.isEmpty())
    }

    // ---- rule 4 ----

    @Test
    fun `state is Starting while the readiness steps run`() = runTest {
        bridge.hangExec = true
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        val done = launchStart(s)
        runCurrent()

        assertTrue(bridge.execEntered.isCompleted)
        assertEquals(ViewerState.Starting, s.state.value)

        bridge.execRelease.complete(Unit)
        awaitStart(done)
        assertTrue(s.state.value is ViewerState.Shared)
    }

    // ---- rule 5 (install path) ----

    @Test
    fun `missing x11vnc is apk-added with the pinned version`() = runTest {
        bridge.execExitCodes.add(127) // command -v x11vnc fails
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        awaitStart(launchStart(s))

        assertEquals("command -v x11vnc", bridge.execCommands[0])
        assertEquals(
            "apk add " + ShellQuote.quote("x11vnc=" + DesktopPackages.VIEWER_PINS.getValue("x11vnc")),
            bridge.execCommands[1],
        )
        assertEquals(VncShare.INSTALL_TIMEOUT_MS, bridge.execTimeoutsMs[1])
        assertTrue(bridge.openedCmds.single().contains("x11vnc"))
    }

    @Test
    fun `failed apk add surfaces an error and opens no stream`() = runTest {
        bridge.execExitCodes.add(1) // command -v fails
        bridge.execExitCodes.add(3) // apk add fails
        val s = share()
        val done = launchStart(s)
        runCurrent()

        assertTrue(done.isCompleted)
        assertTrue(done.getCompleted().isFailure)
        assertErrorState(s.state.value) { it.contains("x11vnc install failed") && it.contains("3") }
        assertTrue(bridge.streams.isEmpty())
    }

    // ---- rule 6 (password write failure) ----

    @Test
    fun `failed password-file write surfaces an error and opens no stream`() = runTest {
        bridge.execExitCodes.add(0) // command -v ok
        bridge.execExitCodes.add(2) // password write fails
        val s = share()
        val done = launchStart(s)
        runCurrent()

        assertTrue(done.isCompleted)
        assertTrue(done.getCompleted().isFailure)
        assertTrue("expected Error but was ${s.state.value}", s.state.value is ViewerState.Error)
        assertTrue(bridge.streams.isEmpty())
    }

    // ---- rule 8 ----

    @Test
    fun `readiness timeout closes the stream and reports an error`() = runTest {
        bridge.onOpen = { } // never announces PORT=
        val s = share()
        val done = launchStart(s)
        runCurrent()
        assertFalse("start() should still be waiting for readiness", done.isCompleted)

        advanceTimeBy(VncShare.READY_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse("start() must not time out early", done.isCompleted)

        advanceTimeBy(2)
        runCurrent()
        assertTrue(done.isCompleted)
        assertTrue(done.getCompleted().isFailure)
        assertErrorState(s.state.value) { it.startsWith("x11vnc did not start") }
        assertEquals(listOf(VncShare.START_FAILED_REASON), bridge.streams.single().closeReasons)
    }

    @Test
    fun `stream exiting before readiness reports an error`() = runTest {
        bridge.onOpen = { it.die(1) }
        val s = share()
        val done = launchStart(s)
        runCurrent()

        assertTrue(done.isCompleted)
        assertTrue(done.getCompleted().isFailure)
        assertErrorState(s.state.value) { it.startsWith("x11vnc did not start") }
    }

    // ---- rule 10 ----

    @Test
    fun `lease expiry stops the viewer with the lease reason`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        val endpoint = awaitStart(launchStart(s, ShareLease(maxMs = 1_000))).getOrThrow()
        assertEquals(1_000, endpoint.expiresAtMs)
        val stream = bridge.streams.single()

        advanceTimeBy(999)
        runCurrent()
        assertTrue(s.state.value is ViewerState.Shared)
        assertTrue(stream.closeReasons.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(VncShare.LEASE_REASON), stream.closeReasons)
        assertEquals(ViewerState.Off, s.state.value)
    }

    @Test
    fun `unexpected x11vnc exit after sharing surfaces an error`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        awaitStart(launchStart(s))

        bridge.streams.single().die(5)
        runCurrent()

        assertErrorState(s.state.value) { it.contains("exited") }
        assertFalse("unexpected exit must not land on Off", s.state.value is ViewerState.Off)
    }

    @Test
    fun `desktop leaving Running stops the viewer`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        awaitStart(launchStart(s))
        val stream = bridge.streams.single()

        bridge.state.value = DesktopState.Stopped
        runCurrent()

        assertEquals(listOf(VncShare.DESKTOP_STOP_REASON), stream.closeReasons)
        assertEquals(ViewerState.Off, s.state.value)
    }

    @Test
    fun `stop closes the stream with the given reason and is idempotent`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        awaitStart(launchStart(s, ShareLease(maxMs = 1_000)))
        val stream = bridge.streams.single()

        s.stop("manual")
        runCurrent()
        assertEquals(listOf("manual"), stream.closeReasons)
        assertEquals(ViewerState.Off, s.state.value)

        s.stop("manual-again")
        runCurrent()
        assertEquals("second stop must not re-close the stream", listOf("manual"), stream.closeReasons)
        assertEquals(ViewerState.Off, s.state.value)
    }

    @Test
    fun `manual stop cancels the pending lease`() = runTest {
        bridge.onOpen = { it.emitOut("PORT=5901") }
        val s = share()
        awaitStart(launchStart(s, ShareLease(maxMs = 1_000)))

        s.stop("manual")
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals("lease must not fire after a manual stop", listOf("manual"), bridge.streams.single().closeReasons)
        assertEquals(ViewerState.Off, s.state.value)
    }

    @Test
    fun `stop while Off is a no-op`() = runTest {
        val s = share()
        s.stop(VncShare.STOP_REASON)
        assertEquals(ViewerState.Off, s.state.value)
        assertTrue(bridge.streams.isEmpty())
    }

    // ---- randomPassword ----

    @Test
    fun `randomPassword is exactly 8 alphanumerics`() {
        repeat(200) {
            val pw = VncShare.randomPassword()
            assertEquals(8, pw.length)
            pw.forEach { c ->
                assertTrue("bad character '$c'", c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')
            }
        }
    }
}
