package com.xdutsuay.chidori.computer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerMotionTest {

    // ------------------------------------------------------------------
    // animationsEnabled
    // ------------------------------------------------------------------

    @Test
    fun `animations enabled at normal scale`() {
        assertTrue(ComputerMotion.animationsEnabled(1f))
    }

    @Test
    fun `animations enabled at half scale`() {
        assertTrue(ComputerMotion.animationsEnabled(0.5f))
    }

    @Test
    fun `animations enabled at large scale`() {
        assertTrue(ComputerMotion.animationsEnabled(10f))
    }

    @Test
    fun `animations enabled at smallest positive float`() {
        assertTrue(ComputerMotion.animationsEnabled(Float.MIN_VALUE))
    }

    @Test
    fun `animations enabled at default system scale`() {
        assertTrue(ComputerMotion.animationsEnabled(ComputerMotion.DEFAULT_ANIMATOR_DURATION_SCALE))
    }

    @Test
    fun `animations disabled at zero`() {
        assertFalse(ComputerMotion.animationsEnabled(0f))
    }

    @Test
    fun `animations disabled at negative zero`() {
        assertFalse(ComputerMotion.animationsEnabled(-0f))
    }

    @Test
    fun `animations disabled for negative scale`() {
        assertFalse(ComputerMotion.animationsEnabled(-1f))
        assertFalse(ComputerMotion.animationsEnabled(-Float.MIN_VALUE))
    }

    @Test
    fun `animations disabled for NaN`() {
        assertFalse(ComputerMotion.animationsEnabled(Float.NaN))
    }

    @Test
    fun `animations disabled for positive infinity`() {
        assertFalse(ComputerMotion.animationsEnabled(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `animations disabled for negative infinity`() {
        assertFalse(ComputerMotion.animationsEnabled(Float.NEGATIVE_INFINITY))
    }

    // ------------------------------------------------------------------
    // constants
    // ------------------------------------------------------------------

    @Test
    fun `durations are positive`() {
        assertTrue(ComputerMotion.TAB_TRANSITION_MS > 0)
        assertTrue(ComputerMotion.RESIZE_MS > 0)
        assertTrue(ComputerMotion.PULSE_PERIOD_MS > 0)
    }

    @Test
    fun `transition and resize durations are short`() {
        assertTrue(ComputerMotion.TAB_TRANSITION_MS <= 400)
        assertTrue(ComputerMotion.RESIZE_MS <= 400)
    }

    @Test
    fun `pulse min alpha is strictly between zero and one`() {
        assertTrue(ComputerMotion.PULSE_MIN_ALPHA > 0f)
        assertTrue(ComputerMotion.PULSE_MIN_ALPHA < 1f)
    }
}
