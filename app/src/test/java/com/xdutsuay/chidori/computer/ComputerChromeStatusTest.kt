package com.xdutsuay.chidori.computer

import com.xdutsuay.chidori.computer.desktop.DesktopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP6 — one coarse chrome status mapped from the richer [DesktopState] machine.
 */
class ComputerChromeStatusTest {

    private val running = DesktopState.Running(
        display = ":1",
        width = 1280,
        height = 720,
        depth = 24,
        xauthorityGuestPath = "/root/.Xauthority",
        framebufferHostFile = File("/tmp/cos-fb/Xvfb_screen0"),
        vncSocketHostPath = null,
    )

    @Test
    fun `every DesktopState variant maps to the right chrome bucket`() {
        assertEquals(
            ComputerChromeStatus.NOT_INSTALLED,
            ComputerChromeStatus.from(DesktopState.NotInstalled),
        )
        assertEquals(
            ComputerChromeStatus.INSTALLING,
            ComputerChromeStatus.from(DesktopState.Installing(0.5f)),
        )
        assertEquals(
            ComputerChromeStatus.INSTALLING,
            ComputerChromeStatus.from(DesktopState.Installing(null)),
        )
        assertEquals(
            ComputerChromeStatus.RUNNING,
            ComputerChromeStatus.from(DesktopState.Starting),
        )
        assertEquals(
            ComputerChromeStatus.RUNNING,
            ComputerChromeStatus.from(running),
        )
        assertEquals(
            ComputerChromeStatus.STOPPED,
            ComputerChromeStatus.from(DesktopState.Stopping),
        )
        assertEquals(
            ComputerChromeStatus.STOPPED,
            ComputerChromeStatus.from(DesktopState.Stopped),
        )
        assertEquals(
            ComputerChromeStatus.STOPPED,
            ComputerChromeStatus.from(DesktopState.Error("xvfb died", null)),
        )
    }

    @Test
    fun `Error carries its message and maps to STOPPED`() {
        val err = DesktopState.Error("boom", RuntimeException("cause"))
        assertEquals("boom", err.message)
        assertEquals(ComputerChromeStatus.STOPPED, ComputerChromeStatus.from(err))
    }

    @Test
    fun `labels are stable english strings`() {
        assertEquals("not installed", ComputerChromeStatus.label(ComputerChromeStatus.NOT_INSTALLED))
        assertEquals("installing", ComputerChromeStatus.label(ComputerChromeStatus.INSTALLING))
        assertEquals("running", ComputerChromeStatus.label(ComputerChromeStatus.RUNNING))
        assertEquals("stopped", ComputerChromeStatus.label(ComputerChromeStatus.STOPPED))
    }

    @Test
    fun `label lookup is consistent with the mapping for a full lifecycle`() {
        val lifecycle = listOf(
            DesktopState.NotInstalled to ComputerChromeStatus.NOT_INSTALLED,
            DesktopState.Installing(0.1f) to ComputerChromeStatus.INSTALLING,
            DesktopState.Starting to ComputerChromeStatus.RUNNING,
            running to ComputerChromeStatus.RUNNING,
            DesktopState.Stopping to ComputerChromeStatus.STOPPED,
            DesktopState.Stopped to ComputerChromeStatus.STOPPED,
        )
        lifecycle.forEach { (state, expected) ->
            val status = ComputerChromeStatus.from(state)
            assertEquals(expected, status)
            assertEquals(
                ComputerChromeStatus.label(expected),
                ComputerChromeStatus.label(status),
            )
        }
    }

    @Test
    fun `showInHeader is exhaustive over every chrome status`() {
        assertFalse(ComputerChromeStatus.showInHeader(ComputerChromeStatus.NOT_INSTALLED))
        assertTrue(ComputerChromeStatus.showInHeader(ComputerChromeStatus.INSTALLING))
        assertTrue(ComputerChromeStatus.showInHeader(ComputerChromeStatus.RUNNING))
        assertTrue(ComputerChromeStatus.showInHeader(ComputerChromeStatus.STOPPED))
        ComputerChromeStatus.entries.forEach { status ->
            val expected = status != ComputerChromeStatus.NOT_INSTALLED
            assertEquals("showInHeader($status)", expected, ComputerChromeStatus.showInHeader(status))
        }
    }
}
