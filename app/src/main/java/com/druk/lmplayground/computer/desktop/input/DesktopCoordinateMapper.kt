package com.druk.lmplayground.computer.desktop.input

import kotlin.math.floor
import kotlin.math.min

/** A desktop pixel. */
data class DesktopPoint(val x: Int, val y: Int)

/**
 * Maps a point in the viewer's view (px, origin top-left of the composable) to a
 * desktop pixel, clamped to the screen. The viewer owns zoom/pan, so it provides
 * the mapper; [LetterboxTransform] covers the usual fit-center + zoom + pan case.
 */
fun interface DesktopCoordinateMapper {
    fun toDesktop(viewX: Float, viewY: Float): DesktopPoint
}

/**
 * Fit-center (letterbox/pillarbox) of a [desktopWidth]x[desktopHeight] frame in a
 * [viewWidth]x[viewHeight] view, then [zoom] about the view center and [panX]/[panY]
 * (view px, applied after zoom), i.e. the frame is drawn at
 * `origin = (view - desktop * base * zoom) / 2 + pan`, `scale = base * zoom`,
 * `base = min(viewW / desktopW, viewH / desktopH)`.
 *
 * This matches `graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX,
 * translationY = panY)` (default center pivot) on a `ContentScale.Fit` image.
 * Points in the letterbox bars or outside the zoomed frame are clamped to the
 * nearest edge pixel.
 */
data class LetterboxTransform(
    val viewWidth: Float,
    val viewHeight: Float,
    val desktopWidth: Int,
    val desktopHeight: Int,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) : DesktopCoordinateMapper {
    init {
        require(viewWidth > 0f && viewHeight > 0f) { "empty view" }
        require(desktopWidth > 0 && desktopHeight > 0) { "empty desktop" }
        require(zoom > 0f) { "zoom must be > 0" }
    }

    val baseScale: Float get() = min(viewWidth / desktopWidth, viewHeight / desktopHeight)
    val scale: Float get() = baseScale * zoom
    val originX: Float get() = (viewWidth - desktopWidth * scale) / 2f + panX
    val originY: Float get() = (viewHeight - desktopHeight * scale) / 2f + panY

    override fun toDesktop(viewX: Float, viewY: Float): DesktopPoint {
        val x = floor((viewX - originX) / scale).toInt().coerceIn(0, desktopWidth - 1)
        val y = floor((viewY - originY) / scale).toInt().coerceIn(0, desktopHeight - 1)
        return DesktopPoint(x, y)
    }

    /** View position of the center of desktop pixel (x, y) (for tests / cursor overlays). */
    fun toView(x: Int, y: Int): Pair<Float, Float> = (originX + (x + 0.5f) * scale) to (originY + (y + 0.5f) * scale)

    /** True when the view point falls on the drawn frame (not the bars). */
    fun isOnFrame(viewX: Float, viewY: Float): Boolean {
        val fx = (viewX - originX) / scale
        val fy = (viewY - originY) / scale
        return fx >= 0f && fy >= 0f && fx < desktopWidth && fy < desktopHeight
    }
}
