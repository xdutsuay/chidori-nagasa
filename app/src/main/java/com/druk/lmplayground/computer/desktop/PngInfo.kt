package com.druk.lmplayground.computer.desktop

/** Minimal PNG header reader (no Android dependency, unit-testable). */
object PngInfo {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size >= 8 && (0 until 8).all { bytes[it] == SIGNATURE[it] }

    /** Width/height from the IHDR chunk, or null if [bytes] is not a PNG. */
    fun size(bytes: ByteArray?): DesktopGeometry? {
        if (bytes == null || bytes.size < 24 || !isPng(bytes)) return null
        // 8 signature + 4 length + "IHDR" + width(4) + height(4), big endian.
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) return null
        val w = be32(bytes, 16)
        val h = be32(bytes, 20)
        return if (w > 0 && h > 0) DesktopGeometry(w, h) else null
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
