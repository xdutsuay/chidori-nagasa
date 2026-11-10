package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class CosResourceFormatTest {

    // ------------------------------------------------------------------
    // cpu
    // ------------------------------------------------------------------

    @Test
    fun `cpu renders em dash for null`() {
        assertEquals("—", CosResourceFormat.cpu(null))
    }

    @Test
    fun `cpu rounds down below half`() {
        assertEquals("12%", CosResourceFormat.cpu(12.4))
    }

    @Test
    fun `cpu rounds up at half`() {
        assertEquals("13%", CosResourceFormat.cpu(12.5))
    }

    @Test
    fun `cpu renders zero percent`() {
        assertEquals("0%", CosResourceFormat.cpu(0.0))
    }

    @Test
    fun `cpu renders full saturation`() {
        assertEquals("100%", CosResourceFormat.cpu(100.0))
    }

    // ------------------------------------------------------------------
    // bytes
    // ------------------------------------------------------------------

    @Test
    fun `bytes renders em dash for null`() {
        assertEquals("—", CosResourceFormat.bytes(null))
    }

    @Test
    fun `bytes renders em dash for negative`() {
        assertEquals("—", CosResourceFormat.bytes(-1L))
    }

    @Test
    fun `bytes delegates to CosRootfsInfo formatBytes`() {
        assertEquals("3.0 MB", CosResourceFormat.bytes(3 * 1024 * 1024))
    }

    @Test
    fun `bytes renders zero and small values`() {
        assertEquals("0 B", CosResourceFormat.bytes(0))
        assertEquals("512 B", CosResourceFormat.bytes(512))
        assertEquals("1.0 KB", CosResourceFormat.bytes(1024))
    }
}
