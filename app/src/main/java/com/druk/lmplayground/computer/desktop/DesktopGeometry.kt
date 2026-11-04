package com.druk.lmplayground.computer.desktop

/** Screen (or image) size in pixels; (0,0) is the top-left pixel. */
data class DesktopGeometry(val width: Int, val height: Int) {
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height
}
