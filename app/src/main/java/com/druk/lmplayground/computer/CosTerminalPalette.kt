package com.druk.lmplayground.computer

import androidx.compose.ui.graphics.Color

/**
 * Warm, quiet terminal colours (ARGB, fully opaque). Kept as plain ints so the
 * contrast guarantee is checkable without Android or Compose runtime.
 */
object CosTerminalPalette {
    /** Warm charcoal background. */
    const val BACKGROUND_ARGB: Int = 0xFF1F1B17.toInt()

    /** Parchment body text. */
    const val FOREGROUND_ARGB: Int = 0xFFE9DFCC.toInt()

    /** Muted sand for the empty-state hint; still readable text. */
    const val MUTED_ARGB: Int = 0xFFB9AA92.toInt()

    /** Soft sage for the Running dot. */
    const val RUNNING_ARGB: Int = 0xFF8FBF8A.toInt()

    /** WCAG 2.x AA minimum contrast for normal-size text. */
    const val MIN_TEXT_CONTRAST = 4.5

    val background: Color get() = Color(BACKGROUND_ARGB)
    val foreground: Color get() = Color(FOREGROUND_ARGB)
    val muted: Color get() = Color(MUTED_ARGB)
    val running: Color get() = Color(RUNNING_ARGB)

    /**
     * WCAG 2.x relative luminance of an opaque ARGB colour, in [0.0, 1.0].
     *
     * Alpha is ignored. Each sRGB channel c = byte / 255 is linearised as
     * `c / 12.92` when `c <= 0.03928`, else `((c + 0.055) / 1.055)^2.4`;
     * luminance = 0.2126 R + 0.7152 G + 0.0722 B. Black is 0.0, white is 1.0.
     */
    fun relativeLuminance(argb: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((argb ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    /**
     * WCAG 2.x contrast ratio `(L1 + 0.05) / (L2 + 0.05)` where L1 is the lighter of
     * the two [relativeLuminance] values. Symmetric in its arguments; ranges from 1.0
     * (identical colours) to 21.0 (black on white).
     */
    fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
        val a = relativeLuminance(foregroundArgb)
        val b = relativeLuminance(backgroundArgb)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
