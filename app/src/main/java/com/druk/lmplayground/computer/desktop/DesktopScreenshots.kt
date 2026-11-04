package com.druk.lmplayground.computer.desktop

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared screenshot encoder for `DesktopBridge.screenshot()`: XWD framebuffer
 * (Xvfb `-fbdir` file, or `xwd -root` output) -> optional downscale -> PNG/JPEG,
 * using [XwdDecoder] (pure Kotlin) and android.graphics.Bitmap. No guest
 * process, no ImageMagick. The desktop runtime's bridge can implement
 * `screenshot(format, scale)` as `DesktopScreenshots.encode(running.framebufferHostFile, format, scale)`.
 */
object DesktopScreenshots {

    const val JPEG_QUALITY = 85

    /** Output size for [scale] (clamped to (0, 1]); never below 1x1. */
    fun scaledSize(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        val s = scale.coerceIn(0.01f, 1f)
        return max(1, (width * s).roundToInt()) to max(1, (height * s).roundToInt())
    }

    fun encode(framebuffer: File, format: ImageFormat = ImageFormat.PNG, scale: Float = 1f): ByteArray =
        encode(XwdDecoder.decode(framebuffer), format, scale)

    fun encode(image: XwdDecoder.Image, format: ImageFormat, scale: Float): ByteArray {
        val src = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
        val (w, h) = scaledSize(image.width, image.height, scale)
        val dst = if (w == image.width && h == image.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        try {
            return ByteArrayOutputStream().use { out ->
                val ok = when (format) {
                    ImageFormat.PNG -> dst.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ImageFormat.JPEG -> dst.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                check(ok) { "bitmap compress failed" }
                out.toByteArray()
            }
        } finally {
            if (dst !== src) dst.recycle()
            src.recycle()
        }
    }
}
