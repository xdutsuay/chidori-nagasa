package com.xdutsuay.chidori.computer.desktop.input

/**
 * Quarter-turn rotation of the COS viewer (display only; Xvfb geometry stays native).
 * [degrees] is always one of 0, 90, 180, 270.
 */
data class DesktopRotation(val quarterTurns: Int = 0) {
    init { require(quarterTurns in 0..3) { "quarterTurns 0..3" } }

    val degrees: Int get() = quarterTurns * 90

    fun next(): DesktopRotation = DesktopRotation((quarterTurns + 1) % 4)

    /**
     * Map a touch in the *visually rotated* view back to the unrotated view space
     * (same space [LetterboxTransform] expects), pivoting about the view center.
     * Inverse of `graphicsLayer(rotationZ = degrees)` (CW).
     */
    fun unrotateView(viewX: Float, viewY: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float> {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        val x = viewX - cx
        val y = viewY - cy
        val (nx, ny) = when (quarterTurns) {
            0 -> x to y
            1 -> y to -x // inverse of 90° CW
            2 -> -x to -y
            else -> -y to x // inverse of 270° CW
        }
        return (cx + nx) to (cy + ny)
    }

    companion object {
        val NONE = DesktopRotation(0)
    }
}

/** Applies [DesktopRotation.unrotateView] then [inner]. */
class RotatingMapper(
    private val inner: DesktopCoordinateMapper,
    private val rotation: DesktopRotation,
    private val viewWidth: Float,
    private val viewHeight: Float,
) : DesktopCoordinateMapper {
    override fun toDesktop(viewX: Float, viewY: Float): DesktopPoint {
        val (ux, uy) = rotation.unrotateView(viewX, viewY, viewWidth, viewHeight)
        return inner.toDesktop(ux, uy)
    }
}
