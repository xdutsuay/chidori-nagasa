package com.druk.lmplayground.computer.desktop.input

import com.druk.lmplayground.computer.desktop.input.InputCommand.ClickAtCursor
import com.druk.lmplayground.computer.desktop.input.InputCommand.MouseMoveRelative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTrackpadGestureTest {
    private val out = mutableListOf<InputCommand>()
    private val identity = LetterboxTransform(1280f, 720f, 1280, 720)

    private fun rec() = DesktopGestureRecognizer(
        identity,
        { out += it },
        DesktopGestureRecognizer.Config(pointerMode = DesktopPointerMode.TRACKPAD, trackpadGain = 1f),
    )

    @Test
    fun `trackpad tap clicks at cursor`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0)
        r.onUp(1, 100f, 100f, 40)
        assertEquals(listOf(ClickAtCursor()), out)
    }

    @Test
    fun `trackpad drag emits relative moves without buttons`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0)
        r.onMove(1, 140f, 120f, 30) // past slop
        r.onMove(1, 160f, 130f, 40)
        r.onUp(1, 160f, 130f, 50)
        assertTrue(out.none { it is InputCommand.MouseDown || it is InputCommand.MouseUp })
        assertTrue(out.any { it is MouseMoveRelative })
        val rel = out.filterIsInstance<MouseMoveRelative>()
        assertEquals(MouseMoveRelative(40, 20), rel.first())
        assertEquals(MouseMoveRelative(20, 10), rel[1])
    }

    @Test
    fun `trackpad long press is right click at cursor`() {
        val r = rec()
        r.onDown(1, 50f, 50f, 0)
        r.onTimer(600)
        assertEquals(listOf(ClickAtCursor(MouseButton.RIGHT)), out)
    }
}
