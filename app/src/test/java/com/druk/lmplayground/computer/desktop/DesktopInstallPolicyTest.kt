package com.druk.lmplayground.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInstallPolicyTest {
    private val gib = 1024L * 1024 * 1024
    private val wifi = DesktopInstallPolicy.NetworkInfo.UNMETERED_WIFI
    private val cell = DesktopInstallPolicy.fromCapabilities(hasInternet = true, notMetered = false, cellular = true)
    private val meteredWifi = DesktopInstallPolicy.fromCapabilities(hasInternet = true, notMetered = false, cellular = false)

    @Test
    fun `size is always shown before download`() {
        val p = DesktopInstallPolicy.prompt("aarch64", false, wifi, 8 * gib)
        assertTrue(p.canInstall)
        assertFalse(p.meteredWarning)
        assertEquals(118_354_286L, p.downloadBytes)
        assertTrue(p.message, p.message.contains("Download: 112.9 MiB"))
        assertTrue(p.message, p.message.contains("on disk: 309.7 MiB"))
        assertEquals("Download ~113 MiB", p.confirmLabel)
        assertEquals("Install desktop (~113 MiB)", DesktopInstallPolicy.menuLabel("aarch64"))
        assertEquals("Install desktop", DesktopInstallPolicy.menuLabel(null))
    }

    @Test
    fun `mobile data and metered wifi warn and need explicit confirm`() {
        val c = DesktopInstallPolicy.prompt("aarch64", false, cell, 8 * gib)
        assertTrue(c.meteredWarning)
        assertTrue(c.canInstall)
        assertTrue(c.message.contains("mobile data"))
        assertEquals("Download anyway (~113 MiB)", c.confirmLabel)
        val m = DesktopInstallPolicy.prompt("aarch64", false, meteredWifi, 8 * gib)
        assertTrue(m.meteredWarning)
        assertTrue(m.message.contains("metered"))
        // NOT_METERED cellular (rare unlimited plans) still warns because it is cellular.
        val unlimitedCell = DesktopInstallPolicy.fromCapabilities(true, notMetered = true, cellular = true)
        assertTrue(DesktopInstallPolicy.prompt("aarch64", false, unlimitedCell, 8 * gib).meteredWarning)
    }

    @Test
    fun `offline, low disk and unsupported arch block`() {
        assertEquals(
            "No network connection",
            DesktopInstallPolicy.prompt("aarch64", false, DesktopInstallPolicy.NetworkInfo.OFFLINE, 8 * gib).blockedReason,
        )
        val low = DesktopInstallPolicy.prompt("aarch64", false, wifi, 100L * 1024 * 1024)
        assertNotNull(low.blockedReason)
        assertTrue(low.blockedReason!!, low.blockedReason.startsWith("Not enough free space: needs 486.5 MiB"))
        assertNotNull(DesktopInstallPolicy.prompt("armv7", false, wifi, 8 * gib).blockedReason)
        assertNotNull(DesktopInstallPolicy.prompt(null, false, wifi, 8 * gib).blockedReason)
    }

    @Test
    fun `firefox adds its size and the soft cap note`() {
        val p = DesktopInstallPolicy.prompt("aarch64", true, wifi, 8 * gib)
        assertEquals(118_354_286L + 115_763_494L, p.downloadBytes)
        assertTrue(p.message.contains("Firefox"))
        assertTrue(p.message.contains("512 MiB"))
        assertEquals(
            DesktopPackages.installedBytes(DesktopPackages.setsFor("aarch64", true)) +
                p.downloadBytes + DesktopInstallPolicy.DISK_MARGIN_BYTES,
            DesktopInstallPolicy.requiredFreeBytes(DesktopPackages.setsFor("aarch64", true)),
        )
        assertNull(p.blockedReason)
    }
}
