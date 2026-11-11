package com.xdutsuay.chidori.computer.desktop.input

import com.xdutsuay.chidori.computer.desktop.input.InputCommand.Click
import com.xdutsuay.chidori.computer.desktop.input.InputCommand.MouseDown
import com.xdutsuay.chidori.computer.desktop.input.InputCommand.MouseMove
import com.xdutsuay.chidori.computer.desktop.input.InputCommand.MouseUp
import com.xdutsuay.chidori.computer.desktop.input.InputCommand.Scroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopGestureRecognizerTest {
    private val out = mutableListOf<InputCommand>()
    private val identity = LetterboxTransform(1280f, 720f, 1280, 720)
    private fun rec(config: DesktopGestureRecognizer.Config = DesktopGestureRecognizer.Config()) =
        DesktopGestureRecognizer(identity, { out += it }, config)

    @Test
    fun `tap is a left click`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0)
        r.onMove(1, 103f, 101f, 20) // within slop
        r.onUp(1, 103f, 101f, 60)
        assertEquals(listOf(Click(100, 100)), out)
        assertFalse(r.consuming)
        r.onTimer(1000)
        assertEquals(1, out.size)
        assertEquals("Idle", r.stateName)
    }

    @Test
    fun `double tap - second click snaps to the first tap`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0); r.onUp(1, 100f, 100f, 50)
        r.onDown(2, 115f, 108f, 200); r.onUp(2, 115f, 108f, 240)
        assertEquals(listOf(Click(100, 100), Click(100, 100)), out)
    }

    @Test
    fun `double tap with deferred single tap is one double click`() {
        val r = rec(DesktopGestureRecognizer.Config(deferSingleTap = true))
        r.onDown(1, 100f, 100f, 0); r.onUp(1, 100f, 100f, 50)
        assertEquals(emptyList<InputCommand>(), out)
        assertEquals(350L, r.nextDeadline())
        r.onDown(2, 104f, 100f, 200); r.onUp(2, 104f, 100f, 240)
        assertEquals(listOf(Click(100, 100, count = 2)), out)
        out.clear()
        r.onDown(3, 500f, 500f, 1000); r.onUp(3, 500f, 500f, 1040)
        r.onTimer(1340)
        assertEquals(listOf(Click(500, 500)), out)
    }

    @Test
    fun `taps too far apart or too late are two single clicks`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0); r.onUp(1, 100f, 100f, 50)
        r.onDown(2, 400f, 100f, 100); r.onUp(2, 400f, 100f, 150)
        r.onDown(3, 400f, 100f, 1000); r.onUp(3, 400f, 100f, 1050)
        assertEquals(listOf(Click(100, 100), Click(400, 100), Click(400, 100)), out)
    }

    @Test
    fun `long press is a right click when the timer fires`() {
        val r = rec()
        r.onDown(1, 300f, 200f, 0)
        assertEquals(500L, r.nextDeadline())
        r.onTimer(499)
        assertTrue(out.isEmpty())
        r.onTimer(500)
        assertEquals(listOf(Click(300, 200, MouseButton.RIGHT)), out)
        r.onMove(1, 350f, 260f, 600) // movement after the long press does nothing
        r.onUp(1, 350f, 260f, 700)
        assertEquals(1, out.size)
        assertNull(r.nextDeadline())
        assertEquals("Idle", r.stateName)
    }

    @Test
    fun `drag is mousedown, moves, mouseup`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0)
        r.onMove(1, 150f, 100f, 20)
        assertTrue(r.consuming)
        r.onMove(1, 200f, 120f, 40)
        r.onTimer(900) // no long press once dragging
        r.onUp(1, 210f, 130f, 60)
        assertEquals(
            listOf(MouseMove(100, 100), MouseDown(), MouseMove(150, 100), MouseMove(200, 120), MouseMove(210, 130), MouseUp()),
            out,
        )
        assertFalse(r.consuming)
    }

    @Test
    fun `cancel during a drag releases the button`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0); r.onMove(1, 200f, 100f, 20)
        r.onCancel()
        assertEquals(MouseUp(), out.last())
        assertEquals("Idle", r.stateName)
    }

    @Test
    fun `two-finger pan scrolls with step accumulation`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0); r.onDown(2, 200f, 100f, 10)
        // Fingers move up alternately by 20 px (span stays ~100 px: not a pinch); centroid -10 per step.
        var y1 = 100f; var y2 = 100f; var t = 20L
        fun step(f: Int) { if (f == 1) { y1 -= 20f; r.onMove(1, 100f, y1, t) } else { y2 -= 20f; r.onMove(2, 200f, y2, t) }; t += 10 }
        step(1)
        assertFalse(r.consuming) // centroid moved 10 px < two-finger slop
        step(2)                  // 20 px → scroll mode, 20 px pending
        assertTrue(r.consuming)
        repeat(8) { step(if (it % 2 == 0) 1 else 2) } // +80 px → 100 px total = 2 clicks, 4 px carried
        r.onUp(1, 100f, y1, t); r.onUp(2, 200f, y2, t + 10)
        assertEquals(listOf(MouseMove(150, 100), Scroll(0, 1), Scroll(0, 1)), out)
        assertEquals("Idle", r.stateName)
    }

    @Test
    fun `pinch is left to the viewer`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0); r.onDown(2, 200f, 100f, 10)
        r.onMove(2, 300f, 100f, 20)
        assertTrue(r.pinching)
        assertFalse(r.consuming)
        r.onMove(1, 50f, 100f, 30)
        r.onUp(1, 50f, 100f, 40); r.onUp(2, 300f, 100f, 50)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `two-finger tap is a right click`() {
        val r = rec()
        r.onDown(1, 100f, 100f, 0); r.onDown(2, 200f, 100f, 10)
        r.onUp(2, 200f, 100f, 80); r.onUp(1, 100f, 100f, 90)
        assertEquals(listOf(Click(100, 100, MouseButton.RIGHT)), out)
    }

    @Test
    fun `mapper changes (zoom, pan, letterbox) apply to the next event`() {
        val r = rec()
        r.mapper = LetterboxTransform(1080f, 1920f, 1280, 720, zoom = 2f, panX = 100f, panY = -50f)
        r.onDown(1, 540f, 960f, 0); r.onUp(1, 540f, 960f, 30)
        r.mapper = LetterboxTransform(1080f, 1920f, 1280, 720)
        r.onDown(2, 540f, 100f, 1000); r.onUp(2, 540f, 100f, 1030) // in the letterbox bar
        assertEquals(listOf(Click(580, 389), Click(640, 0)), out)
    }
}
