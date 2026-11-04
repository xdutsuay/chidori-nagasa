package com.druk.lmplayground.computer.desktop

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downscale math for `cos_screenshot`: the image sent to the model has its longest
 * edge capped at [maxEdge]; [Fit.scale] maps image pixels back to screen pixels
 * (`screen = image * scale`, scale >= 1).
 */
object ScreenshotScaling {
    const val DEFAULT_MAX_EDGE = 1280

    data class Fit(val width: Int, val height: Int, val scale: Double) {
        val downscaled: Boolean get() = scale > 1.0
    }

    fun fit(width: Int, height: Int, maxEdge: Int = DEFAULT_MAX_EDGE): Fit {
        require(width > 0 && height > 0) { "bad geometry ${width}x$height" }
        require(maxEdge > 0) { "bad maxEdge $maxEdge" }
        val longest = max(width, height)
        if (longest <= maxEdge) return Fit(width, height, 1.0)
        val scale = longest.toDouble() / maxEdge
        val w = (width / scale).roundToInt().coerceIn(1, maxEdge)
        val h = (height / scale).roundToInt().coerceIn(1, maxEdge)
        return Fit(w, h, scale)
    }

    /** Image coordinate -> screen coordinate, clamped into [0, screenSize). */
    fun toScreen(imageCoord: Int, scale: Double, screenSize: Int): Int =
        (imageCoord * scale).roundToInt().coerceIn(0, screenSize - 1)

    /** Scale rounded for display in tool output. */
    fun displayScale(scale: Double): Double = (scale * 10_000).roundToInt() / 10_000.0
}
