package com.xdutsuay.chidori.computer.desktop

import java.io.File
import java.io.RandomAccessFile

/**
 * Pure-Kotlin decoder for X Window Dump (XWD, file version 7) images such as the
 * Xvfb `-fbdir` framebuffer file (`Xvfb_screen0`) or `xwd -root` output.
 *
 * Every layout field is read from the header (header size, byte orders, bits
 * per pixel, bytes per line, colour masks, colormap size) rather than assumed.
 * The header's own byte order is detected from `file_version == 7` (xwd writes
 * it MSB-first; Xvfb writes host order). Supports ZPixmap TrueColor/DirectColor
 * at 16, 24 and 32 bits per pixel; output is opaque ARGB_8888 (row-major).
 */
object XwdDecoder {

    const val XWD_FILE_VERSION = 7
    const val Z_PIXMAP = 2
    const val HEADER_FIELDS = 25
    const val COLOR_ENTRY_SIZE = 12
    const val MAX_DIMENSION = 16_384

    class XwdException(message: String) : Exception(message)

    data class Header(
        val headerSize: Int,
        val pixmapFormat: Int,
        val depth: Int,
        val width: Int,
        val height: Int,
        val byteOrder: Int,      // 0 = LSBFirst, 1 = MSBFirst (pixel data)
        val bitsPerPixel: Int,
        val bytesPerLine: Int,
        val visualClass: Int,
        val redMask: Long,
        val greenMask: Long,
        val blueMask: Long,
        val ncolors: Int,
    ) {
        val pixelOffset: Long get() = headerSize.toLong() + ncolors.toLong() * COLOR_ENTRY_SIZE
        val pixelBytes: Long get() = bytesPerLine.toLong() * height
    }

    class Image(val width: Int, val height: Int, val argb: IntArray)

    /** Parses and validates the fixed header (first 100 bytes) of [bytes]. */
    fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size < HEADER_FIELDS * 4) throw XwdException("not an XWD file: ${bytes.size} bytes")
        val bigEndian = when {
            u32(bytes, 4, true) == XWD_FILE_VERSION.toLong() -> true
            u32(bytes, 4, false) == XWD_FILE_VERSION.toLong() -> false
            else -> throw XwdException("not an XWD v7 file")
        }
        fun f(i: Int): Long = u32(bytes, i * 4, bigEndian)
        fun fi(i: Int, name: String): Int {
            val v = f(i)
            if (v > Int.MAX_VALUE) throw XwdException("bad $name $v")
            return v.toInt()
        }
        val h = Header(
            headerSize = fi(0, "header_size"),
            pixmapFormat = fi(2, "pixmap_format"),
            depth = fi(3, "pixmap_depth"),
            width = fi(4, "pixmap_width"),
            height = fi(5, "pixmap_height"),
            byteOrder = fi(7, "byte_order"),
            bitsPerPixel = fi(11, "bits_per_pixel"),
            bytesPerLine = fi(12, "bytes_per_line"),
            visualClass = fi(13, "visual_class"),
            redMask = f(14),
            greenMask = f(15),
            blueMask = f(16),
            ncolors = fi(19, "ncolors"),
        )
        validate(h)
        return h
    }

    private fun validate(h: Header) {
        if (h.headerSize < HEADER_FIELDS * 4 || h.headerSize > 64 * 1024) throw XwdException("bad header_size ${h.headerSize}")
        if (h.pixmapFormat != Z_PIXMAP) throw XwdException("unsupported pixmap_format ${h.pixmapFormat} (need ZPixmap)")
        if (h.width !in 1..MAX_DIMENSION || h.height !in 1..MAX_DIMENSION) throw XwdException("bad size ${h.width}x${h.height}")
        if (h.bitsPerPixel !in setOf(16, 24, 32)) throw XwdException("unsupported bits_per_pixel ${h.bitsPerPixel}")
        if (h.byteOrder !in 0..1) throw XwdException("bad byte_order ${h.byteOrder}")
        if (h.bytesPerLine.toLong() < h.width.toLong() * h.bitsPerPixel / 8) throw XwdException("bytes_per_line ${h.bytesPerLine} too small")
        if (h.ncolors !in 0..65_536) throw XwdException("bad ncolors ${h.ncolors}")
        if (h.redMask == 0L || h.greenMask == 0L || h.blueMask == 0L) throw XwdException("unsupported visual (no colour masks)")
    }

    /** Decodes a complete XWD file held in memory. */
    fun decode(bytes: ByteArray): Image {
        val h = parseHeader(bytes)
        if (h.pixelOffset + h.pixelBytes > bytes.size) {
            throw XwdException("truncated XWD: need ${h.pixelOffset + h.pixelBytes} bytes, have ${bytes.size}")
        }
        return decodePixels(h, bytes, h.pixelOffset.toInt())
    }

    /**
     * Decodes [file] reading only header + pixel rows (the Xvfb fbdir file is a live
     * mmap that Xvfb keeps updating; a frame may tear but is never out of bounds).
     */
    fun decode(file: File): Image {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(HEADER_FIELDS * 4)
            raf.readFully(head)
            val h = parseHeader(head)
            val total = h.pixelOffset + h.pixelBytes
            if (total > raf.length()) throw XwdException("truncated XWD: need $total bytes, have ${raf.length()}")
            if (h.pixelBytes > Int.MAX_VALUE - 8) throw XwdException("framebuffer too large")
            val pixels = ByteArray(h.pixelBytes.toInt())
            raf.seek(h.pixelOffset)
            raf.readFully(pixels)
            return decodePixels(h, pixels, 0)
        }
    }

    private fun decodePixels(h: Header, data: ByteArray, offset: Int): Image {
        val r = Channel(h.redMask)
        val g = Channel(h.greenMask)
        val b = Channel(h.blueMask)
        val bpp = h.bitsPerPixel / 8
        val msb = h.byteOrder == 1
        val out = IntArray(h.width * h.height)
        var o = 0
        for (y in 0 until h.height) {
            var p = offset + y * h.bytesPerLine
            for (x in 0 until h.width) {
                var v = 0L
                if (msb) {
                    for (k in 0 until bpp) v = (v shl 8) or (data[p + k].toLong() and 0xFF)
                } else {
                    for (k in bpp - 1 downTo 0) v = (v shl 8) or (data[p + k].toLong() and 0xFF)
                }
                out[o++] = (0xFF shl 24) or (r.of(v) shl 16) or (g.of(v) shl 8) or b.of(v)
                p += bpp
            }
        }
        return Image(h.width, h.height, out)
    }

    /** Extracts one channel by mask and expands it to 8 bits. */
    private class Channel(mask: Long) {
        private val shift = java.lang.Long.numberOfTrailingZeros(mask)
        private val bits = java.lang.Long.bitCount(mask)
        private val max = (1L shl bits) - 1
        private val m = mask
        fun of(v: Long): Int {
            val c = (v and m) ushr shift
            return if (bits == 8) c.toInt() else ((c * 255 + max / 2) / max).toInt()
        }
    }

    private fun u32(b: ByteArray, o: Int, bigEndian: Boolean): Long {
        val b0 = b[o].toLong() and 0xFF
        val b1 = b[o + 1].toLong() and 0xFF
        val b2 = b[o + 2].toLong() and 0xFF
        val b3 = b[o + 3].toLong() and 0xFF
        return if (bigEndian) (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        else (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }
}
