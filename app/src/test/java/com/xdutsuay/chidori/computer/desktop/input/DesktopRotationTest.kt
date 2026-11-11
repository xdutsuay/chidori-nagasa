package com.xdutsuay.chidori.computer.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopRotationTest {

    @Test
    fun `unrotate 0 is identity`() {
        val r = DesktopRotation(0)
        assertEquals(100f to 200f, r.unrotateView(100f, 200f, 400f, 400f))
    }

    @Test
    fun `unrotate 90 then rotate matches center`() {
        val r = DesktopRotation(1)
        val (ux, uy) = r.unrotateView(300f, 100f, 400f, 400f)
        // 90 CW of (ux-200, uy-200) should land near (100, -100) → view (300,100)
        val x = ux - 200f
        val y = uy - 200f
        val rx = -y
        val ry = x
        assertEquals(300f, 200f + rx, 0.01f)
        assertEquals(100f, 200f + ry, 0.01f)
    }

    @Test
    fun `next cycles quarters`() {
        assertEquals(90, DesktopRotation.NONE.next().degrees)
        assertEquals(0, DesktopRotation(3).next().degrees)
    }

    @Test
    fun `rotating mapper feeds letterbox unrotated coords`() {
        val letterbox = LetterboxTransform(1280f, 720f, 1280, 720)
        val mapper = RotatingMapper(letterbox, DesktopRotation(0), 1280f, 720f)
        assertEquals(DesktopPoint(100, 50), mapper.toDesktop(100.5f, 50.5f))
    }
}
