package com.xdutsuay.chidori.computer.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopStaleCleanupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `removeXLocks deletes lock and socket under rootfs tmp`() {
        val rootfs = tmp.root
        val unix = File(rootfs, "tmp/.X11-unix").apply { mkdirs() }
        val lock = File(rootfs, "tmp/.X7-lock").apply { writeText("12345\n") }
        val sock = File(unix, "X7").apply { writeText("") }
        assertTrue(lock.exists())
        assertTrue(sock.exists())

        DesktopStaleCleanup.removeXLocks(rootfs, 7)

        assertFalse(lock.exists())
        assertFalse(sock.exists())
    }
}
