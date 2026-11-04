package com.druk.lmplayground.computer.desktop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** XWD decoding on synthetic files laid out like Xvfb `-fbdir` / `xwd -root` output. */
class XwdDecoderTest {

    /**
     * Builds an XWD v7 file. [pixel] gives the raw pixel value for (x, y).
     * Header fields are written in [headerOrder]; pixels in [pixelMsb] order.
     */
    private fun xwd(
        width: Int,
        height: Int,
        bpp: Int,
        masks: Triple<Long, Long, Long>,
        headerOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        pixelMsb: Boolean = false,
        ncolors: Int = 0,
        windowName: String = "Xvfb main window\u0000",
        rowPad: Int = 0,
        pixel: (Int, Int) -> Long,
    ): ByteArray {
        val bytesPerLine = width * bpp / 8 + rowPad
        val headerSize = 100 + windowName.length
        val hb = ByteBuffer.allocate(100).order(headerOrder)
        val fields = longArrayOf(
            headerSize.toLong(), 7, 2, 24, width.toLong(), height.toLong(), 0,
            if (pixelMsb) 1 else 0, 32, 0, 32, bpp.toLong(), bytesPerLine.toLong(), 4,
            masks.first, masks.second, masks.third, 8, 256, ncolors.toLong(),
            width.toLong(), height.toLong(), 0, 0, 0,
        )
        fields.forEach { hb.putInt(it.toInt()) }
        val out = ByteArrayOutputStream()
        out.write(hb.array())
        out.write(windowName.toByteArray(Charsets.ISO_8859_1))
        out.write(ByteArray(ncolors * 12) { 0x5A })
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = pixel(x, y)
                val n = bpp / 8
                val b = ByteArray(n)
                for (k in 0 until n) {
                    val shift = 8 * (if (pixelMsb) n - 1 - k else k)
                    b[k] = ((v ushr shift) and 0xFF).toByte()
                }
                out.write(b)
            }
            out.write(ByteArray(rowPad) { 0x7F })
        }
        return out.toByteArray()
    }

    private val rgb888 = Triple(0xFF0000L, 0x00FF00L, 0x0000FFL)

    @Test
    fun `decodes 32bpp BGRX little-endian like Xvfb fbdir with a 256-entry colormap`() {
        // Same layout as the spike's 1280x720 sample: header + window name, 256*12 colormap.
        val data = xwd(4, 3, 32, rgb888, ncolors = 256) { x, y ->
            (0xAAL shl 24) or ((x * 60L) shl 16) or ((y * 100L) shl 8) or 0x11L
        }
        val h = XwdDecoder.parseHeader(data)
        assertEquals(4, h.width)
        assertEquals(3, h.height)
        assertEquals(32, h.bitsPerPixel)
        assertEquals(h.headerSize + 256L * 12, h.pixelOffset)
        val img = XwdDecoder.decode(data)
        assertEquals(12, img.argb.size)
        // Alpha forced opaque, padding byte (0xAA) ignored.
        assertEquals(0xFF000011.toInt(), img.argb[0])
        assertEquals(0xFFB4C811.toInt(), img.argb[2 * 4 + 3]) // x=3 -> 180, y=2 -> 200
    }

    @Test
    fun `header byte order detected from file_version, MSB header and MSB pixels`() {
        val data = xwd(2, 2, 32, rgb888, headerOrder = ByteOrder.BIG_ENDIAN, pixelMsb = true) { x, y ->
            if (x == 1 && y == 1) 0x00123456L else 0x00FFFFFFL
        }
        val img = XwdDecoder.decode(data)
        assertEquals(0xFFFFFFFF.toInt(), img.argb[0])
        assertEquals(0xFF123456.toInt(), img.argb[3])
    }

    @Test
    fun `16bpp RGB565 expands channels to 8 bits`() {
        val masks = Triple(0xF800L, 0x07E0L, 0x001FL)
        val data = xwd(3, 1, 16, masks) { x, _ ->
            when (x) {
                0 -> 0xF800L // red max
                1 -> 0x07E0L // green max
                else -> 0x0010L // blue 16/31
            }
        }
        val img = XwdDecoder.decode(data)
        assertEquals(0xFFFF0000.toInt(), img.argb[0])
        assertEquals(0xFF00FF00.toInt(), img.argb[1])
        assertEquals(0xFF000084.toInt(), img.argb[2]) // round(16*255/31) = 132
    }

    @Test
    fun `24bpp with padded rows honours bytes_per_line`() {
        val data = xwd(3, 2, 24, rgb888, rowPad = 3) { x, y -> if (y == 1 && x == 2) 0x0A0B0CL else 0L }
        val img = XwdDecoder.decode(data)
        assertEquals(0xFF000000.toInt(), img.argb[0])
        assertEquals(0xFF0A0B0C.toInt(), img.argb[5])
    }

    @Test
    fun `swapped masks map to the right channels`() {
        val bgr = Triple(0x0000FFL, 0x00FF00L, 0xFF0000L)
        val data = xwd(1, 1, 32, bgr) { _, _ -> 0x00332211L }
        assertEquals(0xFF112233.toInt(), XwdDecoder.decode(data).argb[0])
    }

    @Test
    fun `file decode reads only header and pixels and matches in-memory decode`() {
        val data = xwd(5, 4, 32, rgb888, ncolors = 256) { x, y -> (x * 40L shl 16) or (y * 50L) }
        val f = File.createTempFile("xvfb_screen0", ".xwd")
        try {
            f.writeBytes(data)
            assertArrayEquals(XwdDecoder.decode(data).argb, XwdDecoder.decode(f).argb)
        } finally {
            f.delete()
        }
    }

    private fun assertRejects(data: ByteArray, contains: String) {
        try {
            XwdDecoder.decode(data)
            fail("expected XwdException ($contains)")
        } catch (e: XwdDecoder.XwdException) {
            assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    @Test
    fun `rejects truncated, non-XWD and unsupported files`() {
        val good = xwd(4, 4, 32, rgb888) { _, _ -> 0L }
        assertRejects(good.copyOf(good.size - 1), "truncated")
        assertRejects(ByteArray(10), "not an XWD")
        assertRejects(ByteArray(100), "not an XWD v7")
        // pixmap_format XYPixmap (1)
        val xy = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1) }
        assertRejects(xy, "pixmap_format")
        // 8 bpp
        val bpp8 = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(44, 8) }
        assertRejects(bpp8, "bits_per_pixel")
        // bytes_per_line smaller than a row
        val bpl = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(48, 4) }
        assertRejects(bpl, "bytes_per_line")
        // absurd size
        val huge = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 100_000) }
        assertRejects(huge, "bad size")
        // no colour masks (PseudoColor)
        val nomask = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(56, 0) }
        assertRejects(nomask, "colour masks")
    }
}
