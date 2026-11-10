package com.druk.lmplayground.computer.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeDetectorTest {

    private fun pixels(vararg argbs: Int): IntArray = intArrayOf(*argbs)

    @Test
    fun `first call reports a new frame`() {
        val detector = FrameChangeDetector()
        assertTrue(detector.isNew(2, 2, pixels(1, 2, 3, 4)))
    }

    @Test
    fun `identical frame is not new even from a different array instance`() {
        val detector = FrameChangeDetector()
        assertTrue(detector.isNew(2, 2, pixels(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)))
        assertFalse(detector.isNew(2, 2, intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)))
    }

    @Test
    fun `any pixel difference reports a new frame`() {
        val detector = FrameChangeDetector()
        assertTrue(detector.isNew(2, 2, pixels(1, 2, 3, 4)))
        assertFalse(detector.isNew(2, 2, pixels(1, 2, 3, 4)))
        assertTrue(detector.isNew(2, 2, pixels(1, 2, 3, 5)))
    }

    @Test
    fun `size change reports a new frame even with same pixel count`() {
        val detector = FrameChangeDetector()
        val content = intArrayOf(1, 2, 3, 4, 5, 6)
        assertTrue(detector.isNew(2, 3, content))
        assertFalse(detector.isNew(2, 3, content))
        assertTrue(detector.isNew(3, 2, content))
    }

    @Test
    fun `mutating the caller array after acceptance does not poison the baseline`() {
        val detector = FrameChangeDetector()
        val arr = pixels(10, 20, 30, 40)
        assertTrue(detector.isNew(2, 2, arr))
        arr[0] = 999
        // Baseline must still be the ORIGINAL accepted content (detector copied it),
        // so resubmitting that same original content is not a new frame.
        assertFalse(detector.isNew(2, 2, pixels(10, 20, 30, 40)))
    }

    @Test
    fun `mutating the caller array then resubmitting reports new`() {
        val detector = FrameChangeDetector()
        val arr = pixels(10, 20, 30, 40)
        assertTrue(detector.isNew(2, 2, arr))
        arr[0] = 999
        assertTrue(detector.isNew(2, 2, arr))
    }

    @Test
    fun `reset makes the next call report a new frame`() {
        val detector = FrameChangeDetector()
        val arr = pixels(1, 2, 3, 4)
        assertTrue(detector.isNew(2, 2, arr))
        assertFalse(detector.isNew(2, 2, arr))
        detector.reset()
        assertTrue(detector.isNew(2, 2, arr))
    }

    @Test
    fun `accepted frame becomes the comparison baseline`() {
        val detector = FrameChangeDetector()
        assertTrue(detector.isNew(1, 2, pixels(5, 6)))
        assertFalse(detector.isNew(1, 2, pixels(5, 6)))
        assertTrue(detector.isNew(1, 2, pixels(5, 7))) // accepted, new baseline
        assertFalse(detector.isNew(1, 2, pixels(5, 7)))
    }
}
