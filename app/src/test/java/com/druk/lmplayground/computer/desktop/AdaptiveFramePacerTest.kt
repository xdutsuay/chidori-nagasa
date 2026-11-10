package com.druk.lmplayground.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveFramePacerTest {

    @Test
    fun `defaults are 66ms min and 500ms max`() {
        val pacer = AdaptiveFramePacer()
        assertEquals(66L, pacer.currentIntervalMs)
    }

    @Test
    fun `unchanged frames double the interval up to the cap`() {
        val pacer = AdaptiveFramePacer()
        assertEquals(132L, pacer.onFrame(changed = false))
        assertEquals(264L, pacer.onFrame(changed = false))
        assertEquals(500L, pacer.onFrame(changed = false))
        assertEquals(500L, pacer.onFrame(changed = false))
        assertEquals(500L, pacer.currentIntervalMs)
    }

    @Test
    fun `changed frame resets the interval to min`() {
        val pacer = AdaptiveFramePacer()
        pacer.onFrame(changed = false)
        pacer.onFrame(changed = false)
        assertEquals(264L, pacer.currentIntervalMs)
        assertEquals(66L, pacer.onFrame(changed = true))
        assertEquals(66L, pacer.currentIntervalMs)
    }

    @Test
    fun `onInput after backoff resets to min`() {
        val pacer = AdaptiveFramePacer()
        pacer.onFrame(changed = false)
        pacer.onFrame(changed = false)
        pacer.onFrame(changed = false)
        assertEquals(500L, pacer.currentIntervalMs)
        pacer.onInput()
        assertEquals(66L, pacer.currentIntervalMs)
    }

    @Test
    fun `custom bounds are respected`() {
        val pacer = AdaptiveFramePacer(minIntervalMs = 10L, maxIntervalMs = 35L)
        assertEquals(10L, pacer.currentIntervalMs)
        assertEquals(20L, pacer.onFrame(changed = false))
        assertEquals(35L, pacer.onFrame(changed = false))
        assertEquals(35L, pacer.onFrame(changed = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero min interval is rejected`() {
        AdaptiveFramePacer(minIntervalMs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative min interval is rejected`() {
        AdaptiveFramePacer(minIntervalMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `max below min is rejected`() {
        AdaptiveFramePacer(minIntervalMs = 100L, maxIntervalMs = 50L)
    }
}
