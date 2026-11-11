package com.xdutsuay.chidori.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * Runtime-side check of the shared [XwdDecoder] (LightYagami's, a0ab138) against XWD
 * fixtures that match Alpine/Xvfb layout (160x120x24, header sizes 160 / 107, 256-entry
 * colormap, BGRX LE). The box's real captures were omitted from the incomplete binary
 * section of `wt-desktop-uncommitted.patch`, so these gzipped samples are synthetic
 * stand-ins with the same header geometry and a navy/yellow/black pixel set; CRCs
 * below match those stand-ins. Restore the box PNGs/XWDs when available.
 * `xvfb-fbdir-*` is what [LocalDesktopBridge.screenshot] hands to
 * [DesktopScreenshots.encode]; `xwd-root-*` is `xwd -root` of the same screen.
 * Broader format matrix: [XwdDecoderTest].
 */
class XwdRealSampleTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sample(name: String): ByteArray =
        GZIPInputStream(javaClass.getResourceAsStream("/xwd/$name")!!).use { it.readBytes() }

    private fun crc(argb: IntArray): String {
        val bb = ByteBuffer.allocate(argb.size * 4)
        argb.forEach { bb.putInt(it) }
        return "%08x".format(CRC32().apply { update(bb.array()) }.value)
    }

    private val navy = 0xff000080.toInt()
    private val black = 0xff000000.toInt()
    private val yellow = 0xffffff00.toInt()

    @Test
    fun `Xvfb fbdir header matches what the runtime launches`() {
        val h = XwdDecoder.parseHeader(sample("xvfb-fbdir-160x120.xwd.gz"))
        assertEquals(XwdDecoder.Z_PIXMAP, h.pixmapFormat)
        assertEquals(24, h.depth)
        assertEquals(160, h.width)
        assertEquals(120, h.height)
        assertEquals(32, h.bitsPerPixel)
        assertEquals(640, h.bytesPerLine)
        assertEquals(0xff0000L, h.redMask)
        assertEquals(0xff00L, h.greenMask)
        assertEquals(0xffL, h.blueMask)
        assertEquals(0, h.byteOrder)
        assertEquals(256, h.ncolors)
        assertEquals(160, h.headerSize)
        assertEquals(3232L, h.pixelOffset)
    }

    @Test
    fun `fbdir file decodes from disk like the live framebuffer`() {
        // decode(File) is the path the runtime uses on the mmapped Xvfb_screen0 file.
        val f = tmp.newFile("Xvfb_screen0").apply { writeBytes(sample("xvfb-fbdir-160x120.xwd.gz")) }
        val img = XwdDecoder.decode(f)
        assertEquals(160, img.width)
        assertEquals(120, img.height)
        assertEquals(black, img.argb[0])
        assertEquals(navy, img.argb[12 * 160 + 12]) // inside the xterm
        assertEquals(setOf(black, navy, yellow), img.argb.toSet())
        assertEquals("3a2b826a", crc(img.argb))
    }

    @Test
    fun `xwd -root output decodes to the same screen`() {
        val data = sample("xwd-root-160x120.xwd.gz")
        val h = XwdDecoder.parseHeader(data)
        assertEquals(107, h.headerSize)
        assertEquals(3179L, h.pixelOffset)
        val img = XwdDecoder.decode(data)
        assertEquals(navy, img.argb[12 * 160 + 12])
        assertEquals(setOf(black, navy, yellow), img.argb.toSet())
        assertEquals("3a2b826a", crc(img.argb))
    }
}
