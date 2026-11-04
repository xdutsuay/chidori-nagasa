package com.druk.lmplayground.computer.desktop

import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.computer.desktop.DesktopStateMachine.Event
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesktopStateMachineTest {

    private val running = DesktopState.Running(":42", 1280, 720, 24, "/var/lib/cos-desktop/Xauthority", File("/x"), null)

    private fun run(start: DesktopState, vararg events: Event): DesktopState =
        events.fold(start) { s, e -> DesktopStateMachine.next(s, e) ?: error("$e rejected in $s") }

    @Test
    fun `happy path install start stop`() {
        val s = run(
            DesktopState.NotInstalled,
            Event.InstallStarted, Event.InstallProgress(0.5f), Event.InstallSucceeded,
            Event.StartRequested, Event.Ready(running),
        )
        assertEquals(running, s)
        assertEquals(DesktopState.Stopped, run(s, Event.StopRequested, Event.Stopped))
    }

    @Test
    fun `progress is clamped and kept when unknown`() {
        val s = run(DesktopState.NotInstalled, Event.InstallStarted, Event.InstallProgress(1.7f))
        assertEquals(DesktopState.Installing(1f), s)
        assertEquals(DesktopState.Installing(1f), DesktopStateMachine.next(s, Event.InstallProgress(null)))
    }

    @Test
    fun `failures land in Error and Start is allowed again from Error`() {
        val installErr = run(DesktopState.NotInstalled, Event.InstallStarted, Event.InstallFailed("no net"))
        assertEquals(DesktopState.Error("no net", null), installErr)
        val startErr = run(DesktopState.Stopped, Event.StartRequested, Event.StartFailed("busy"))
        assertEquals(DesktopState.Error("busy", null), startErr)
        assertEquals(DesktopState.Starting, DesktopStateMachine.next(startErr, Event.StartRequested))
        val died = run(running, Event.Exited("rc=1"))
        assertEquals(DesktopState.Error("rc=1", null), died)
    }

    @Test
    fun `busy states reject conflicting events`() {
        assertNull(DesktopStateMachine.next(DesktopState.NotInstalled, Event.StartRequested))
        assertNull(DesktopStateMachine.next(running, Event.StartRequested))
        assertNull(DesktopStateMachine.next(running, Event.InstallStarted))
        assertNull(DesktopStateMachine.next(DesktopState.Stopping, Event.StartRequested))
        assertNull(DesktopStateMachine.next(DesktopState.Stopped, Event.StopRequested))
        assertNull(DesktopStateMachine.next(DesktopState.Installing(null), Event.StartRequested))
        assertNull(DesktopStateMachine.next(DesktopState.Installing(null), Event.Uninstalled))
        assertNull(DesktopStateMachine.next(running, Event.Uninstalled))
        assertNull(DesktopStateMachine.next(DesktopState.Stopped, Event.Ready(running)))
        assertTrue(DesktopStateMachine.canStop(DesktopState.Starting))
        assertFalse(DesktopStateMachine.canInstall(running))
        assertEquals(DesktopState.NotInstalled, DesktopStateMachine.next(DesktopState.Error("x", null), Event.Uninstalled))
    }

    @Test
    fun labels() {
        assertEquals("Installing 50% · xvfb", DesktopStateMachine.label(DesktopState.Installing(0.5f), "xvfb"))
        assertEquals("Running :42 · 1280×720×24", DesktopStateMachine.label(running))
        assertEquals("Error: boom", DesktopStateMachine.label(DesktopState.Error("boom", null)))
    }

    @Test
    fun `scripted fake bridge follows the contract and the machine`() = runBlocking {
        val b = ScriptedDesktopBridge(DesktopState.NotInstalled)
        assertEquals(127, b.execInDesktop("xdotool getmouselocation").exitCode)
        assertTrue(runCatching { b.screenshot() }.isFailure)
        assertTrue(b.start().isFailure)
        assertEquals(DesktopState.Stopped, b.install())
        val r = b.start().getOrThrow()
        assertTrue(b.isDesktopRunning())
        assertEquals(r, b.start().getOrThrow()) // idempotent
        b.execHandler = { ComputerExecResult(0, "x:1 y:2", "") }
        assertEquals("x:1 y:2", b.execInDesktop("xdotool getmouselocation").stdout)
        assertEquals(1, b.screenshot(ImageFormat.JPEG, 0.5f).size)
        b.stop()
        assertEquals(DesktopState.Stopped, b.state.value)
        b.startError = "display busy"
        assertTrue(b.start().isFailure)
        assertEquals(DesktopState.Error("display busy", null), b.state.value)
        assertEquals(
            listOf(
                "exec:xdotool getmouselocation", "screenshot:PNG:1.0", "start", "install", "start", "start",
                "exec:xdotool getmouselocation", "screenshot:JPEG:0.5", "stop", "start",
            ),
            b.calls.toList(),
        )
    }
}
