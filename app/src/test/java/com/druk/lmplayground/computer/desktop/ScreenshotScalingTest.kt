package com.druk.lmplayground.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotScalingTest {
    @Test
    fun `screens within max edge are not scaled`() {
        val f = ScreenshotScaling.fit(1280, 720)
        assertEquals(ScreenshotScaling.Fit(1280, 720, 1.0), f)
        assertFalse(f.downscaled)
        assertEquals(ScreenshotScaling.Fit(800, 600, 1.0), ScreenshotScaling.fit(800, 600))
    }

    @Test
    fun `larger screens fit the longest edge to 1280`() {
        val f = ScreenshotScaling.fit(1920, 1080)
        assertEquals(1280, f.width)
        assertEquals(720, f.height)
        assertEquals(1.5, f.scale, 1e-9)
        assertTrue(f.downscaled)
        val portrait = ScreenshotScaling.fit(1080, 2400)
        assertEquals(1280, portrait.height)
        assertEquals(576, portrait.width)
        assertEquals(2400.0 / 1280, portrait.scale, 1e-9)
        val small = ScreenshotScaling.fit(1920, 1080, maxEdge = 640)
        assertEquals(640 to 360, small.width to small.height)
        assertEquals(3.0, small.scale, 1e-9)
    }

    @Test
    fun `image coordinates map back to clamped screen coordinates`() {
        assertEquals(150, ScreenshotScaling.toScreen(100, 1.5, 1920))
        assertEquals(1919, ScreenshotScaling.toScreen(1279, 1.5, 1920)) // 1918.5 -> 1919
        assertEquals(1919, ScreenshotScaling.toScreen(5000, 1.5, 1920))
        assertEquals(0, ScreenshotScaling.toScreen(0, 1.5, 1920))
        assertEquals(1.875, ScreenshotScaling.displayScale(2400.0 / 1280), 1e-9)
        assertEquals(1.3333, ScreenshotScaling.displayScale(4.0 / 3), 1e-9)
    }

    @Test
    fun `png size is read from IHDR`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0, 0, 0x05, 0x00, 0, 0, 0x02, 0xD0.toByte(),
        )
        assertTrue(PngInfo.isPng(png))
        assertEquals(DesktopGeometry(1280, 720), PngInfo.size(png))
        assertNull(PngInfo.size(byteArrayOf(1, 2, 3)))
        assertNull(PngInfo.size(null))
    }

    @Test
    fun `geometry contains is half-open`() {
        val g = DesktopGeometry(1280, 720)
        assertTrue(g.contains(0, 0))
        assertTrue(g.contains(1279, 719))
        assertFalse(g.contains(1280, 0))
        assertFalse(g.contains(0, 720))
        assertFalse(g.contains(-1, 5))
    }
}
