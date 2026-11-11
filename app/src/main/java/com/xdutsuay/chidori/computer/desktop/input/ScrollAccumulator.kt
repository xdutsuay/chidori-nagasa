package com.xdutsuay.chidori.computer.desktop.input

import kotlin.math.abs
import kotlin.math.truncate

/**
 * Converts finger travel (view px) into whole wheel clicks: every [stepPx] of
 * travel is one click, the remainder carries over to the next event (so slow
 * scrolls still scroll), and [reset] drops it at the end of a gesture.
 *
 * With [natural] scrolling (Android default) content follows the finger: moving
 * the fingers up (negative dy) scrolls down (positive wheel dy).
 */
class ScrollAccumulator(private val stepPx: Float, private val natural: Boolean = true) {
    init { require(stepPx > 0f) }

    private var accX = 0f
    private var accY = 0f

    /** Adds finger travel; returns wheel clicks (dx, dy), > 0 = right/down. */
    fun add(fingerDx: Float, fingerDy: Float): Pair<Int, Int> {
        val sign = if (natural) -1f else 1f
        accX += sign * fingerDx
        accY += sign * fingerDy
        val cx = truncate(accX / stepPx).toInt()
        val cy = truncate(accY / stepPx).toInt()
        accX -= cx * stepPx
        accY -= cy * stepPx
        return cx to cy
    }

    fun reset() { accX = 0f; accY = 0f }

    val pendingPx: Float get() = maxOf(abs(accX), abs(accY))
}
