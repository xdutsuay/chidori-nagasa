package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CosRootfsInfoTest {

    @Test
    fun `parseMode reads alpine-tar from marker`() {
        val marker = """
            channel=alpine-minirootfs-pinned
            mode=alpine-tar
            version=3.20.3
        """.trimIndent()
        assertEquals(CosRootfsInfo.MODE_ALPINE_TAR, CosRootfsInfo.parseMode(marker))
    }

    @Test
    fun `parseMode reads offline-stub from marker`() {
        val marker = "channel=x\nmode=offline-stub\ndistro=COS\n"
        assertEquals(CosRootfsInfo.MODE_OFFLINE_STUB, CosRootfsInfo.parseMode(marker))
    }

    @Test
    fun `parseMode returns null when mode missing`() {
        assertNull(CosRootfsInfo.parseMode("channel=alpine-minirootfs-pinned\n"))
        assertNull(CosRootfsInfo.parseMode(""))
    }

    @Test
    fun `parseMode trims whitespace and ignores blank mode`() {
        assertEquals("alpine-tar", CosRootfsInfo.parseMode("  mode=alpine-tar  "))
        assertNull(CosRootfsInfo.parseMode("mode=\n"))
    }

    @Test
    fun `shortModeLabel maps known modes`() {
        assertEquals("alpine", CosRootfsInfo.shortModeLabel(CosRootfsInfo.MODE_ALPINE_TAR))
        assertEquals("stub", CosRootfsInfo.shortModeLabel(CosRootfsInfo.MODE_OFFLINE_STUB))
        assertEquals("none", CosRootfsInfo.shortModeLabel(null))
        assertEquals("none", CosRootfsInfo.shortModeLabel(""))
        assertEquals("custom", CosRootfsInfo.shortModeLabel("custom"))
    }

    @Test
    fun `formatBytes human readable`() {
        assertEquals("0 B", CosRootfsInfo.formatBytes(0))
        assertEquals("512 B", CosRootfsInfo.formatBytes(512))
        assertEquals("1.0 KB", CosRootfsInfo.formatBytes(1024))
        assertEquals("12.4 MB", CosRootfsInfo.formatBytes((12.4 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", CosRootfsInfo.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `statusLine matches Rootfs alpine stub pattern`() {
        assertEquals(
            "Rootfs: alpine · 12.4 MB",
            CosRootfsInfo.statusLine(
                CosRootfsInfo.MODE_ALPINE_TAR,
                (12.4 * 1024 * 1024).toLong(),
            ),
        )
        assertEquals(
            "Rootfs: stub · 8 B",
            CosRootfsInfo.statusLine(CosRootfsInfo.MODE_OFFLINE_STUB, 8),
        )
        assertEquals(
            "Rootfs: none · 0 B",
            CosRootfsInfo.statusLine(null, 0),
        )
    }

    @Test
    fun `snapshot statusLine delegates to CosRootfsInfo`() {
        val snap = CosRootfsSnapshot(
            mode = CosRootfsInfo.MODE_ALPINE_TAR,
            usedBytes = 2048,
            present = true,
        )
        assertEquals(CosRootfsInfo.statusLine(snap.mode, snap.usedBytes), snap.statusLine)
    }
}
