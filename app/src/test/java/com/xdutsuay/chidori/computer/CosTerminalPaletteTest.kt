package com.xdutsuay.chidori.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosTerminalPaletteTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val exact = 1e-9

    // ------------------------------------------------------------------
    // relativeLuminance
    // ------------------------------------------------------------------

    @Test
    fun `luminance of black is zero`() {
        assertEquals(0.0, CosTerminalPalette.relativeLuminance(black), exact)
    }

    @Test
    fun `luminance of white is one`() {
        assertEquals(1.0, CosTerminalPalette.relativeLuminance(white), exact)
    }

    @Test
    fun `luminance of pure red is red coefficient`() {
        assertEquals(0.2126, CosTerminalPalette.relativeLuminance(0xFFFF0000.toInt()), exact)
    }

    @Test
    fun `luminance of pure green is green coefficient`() {
        assertEquals(0.7152, CosTerminalPalette.relativeLuminance(0xFF00FF00.toInt()), exact)
    }

    @Test
    fun `luminance of pure blue is blue coefficient`() {
        assertEquals(0.0722, CosTerminalPalette.relativeLuminance(0xFF0000FF.toInt()), exact)
    }

    @Test
    fun `luminance ignores alpha`() {
        assertEquals(
            CosTerminalPalette.relativeLuminance(white),
            CosTerminalPalette.relativeLuminance(0x00FFFFFF),
            exact,
        )
    }

    // ------------------------------------------------------------------
    // contrastRatio
    // ------------------------------------------------------------------

    @Test
    fun `black on white is 21`() {
        assertEquals(21.0, CosTerminalPalette.contrastRatio(black, white), exact)
    }

    @Test
    fun `contrast ratio is symmetric`() {
        assertEquals(21.0, CosTerminalPalette.contrastRatio(white, black), exact)
        val a = 0xFF777777.toInt()
        assertEquals(
            CosTerminalPalette.contrastRatio(a, white),
            CosTerminalPalette.contrastRatio(white, a),
            exact,
        )
    }

    @Test
    fun `identical colours have ratio one`() {
        assertEquals(1.0, CosTerminalPalette.contrastRatio(white, white), exact)
        assertEquals(1.0, CosTerminalPalette.contrastRatio(black, black), exact)
        val mid = 0xFF777777.toInt()
        assertEquals(1.0, CosTerminalPalette.contrastRatio(mid, mid), exact)
    }

    @Test
    fun `grey 777777 on white matches WCAG reference`() {
        assertEquals(4.48, CosTerminalPalette.contrastRatio(0xFF777777.toInt(), white), 0.01)
    }

    // ------------------------------------------------------------------
    // palette guarantee
    // ------------------------------------------------------------------

    @Test
    fun `min text contrast is WCAG AA`() {
        assertEquals(4.5, CosTerminalPalette.MIN_TEXT_CONTRAST, 0.0)
    }

    @Test
    fun `foreground on background meets min text contrast`() {
        val ratio = CosTerminalPalette.contrastRatio(
            CosTerminalPalette.FOREGROUND_ARGB,
            CosTerminalPalette.BACKGROUND_ARGB,
        )
        assertTrue("ratio=$ratio", ratio >= CosTerminalPalette.MIN_TEXT_CONTRAST)
    }

    @Test
    fun `muted on background meets min text contrast`() {
        val ratio = CosTerminalPalette.contrastRatio(
            CosTerminalPalette.MUTED_ARGB,
            CosTerminalPalette.BACKGROUND_ARGB,
        )
        assertTrue("ratio=$ratio", ratio >= CosTerminalPalette.MIN_TEXT_CONTRAST)
    }

    @Test
    fun `palette colours are fully opaque`() {
        listOf(
            CosTerminalPalette.BACKGROUND_ARGB,
            CosTerminalPalette.FOREGROUND_ARGB,
            CosTerminalPalette.MUTED_ARGB,
            CosTerminalPalette.RUNNING_ARGB,
        ).forEach { argb ->
            assertEquals(0xFF, (argb ushr 24) and 0xFF)
        }
    }
}
