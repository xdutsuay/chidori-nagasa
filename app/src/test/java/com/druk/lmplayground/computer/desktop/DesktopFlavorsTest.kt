package com.druk.lmplayground.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFlavorsTest {

    @Test
    fun `fromId falls back to openbox`() {
        assertEquals(DesktopFlavor.XFCE, DesktopFlavor.fromId("xfce"))
        assertEquals(DesktopFlavor.OPENBOX, DesktopFlavor.fromId("openbox"))
        assertEquals(DesktopFlavor.OPENBOX, DesktopFlavor.fromId(null))
        assertEquals(DesktopFlavor.OPENBOX, DesktopFlavor.fromId("junk"))
    }

    @Test
    fun `packagesFor adds xfce and firefox only when chosen`() {
        val base = DesktopFlavors.packagesFor(DesktopFlavor.OPENBOX, firefox = false)
        assertEquals(DesktopPackages.DEFAULT_PACKAGES, base)

        val xfce = DesktopFlavors.packagesFor(DesktopFlavor.XFCE, firefox = false)
        assertTrue(xfce.containsAll(DesktopFlavors.XFCE_PACKAGES))
        assertTrue(DesktopPackages.FIREFOX_PACKAGES.none { it in xfce })

        val both = DesktopFlavors.packagesFor(DesktopFlavor.XFCE, firefox = true)
        assertTrue(both.containsAll(DesktopFlavors.XFCE_PACKAGES + DesktopPackages.FIREFOX_PACKAGES))
    }

    @Test
    fun `openbox session paints the background and keeps the openbox order`() {
        val s = DesktopFlavors.sessionCommands(DesktopFlavor.OPENBOX)
        assertTrue(s.contains("xsetroot -solid ${DesktopFlavors.OPENBOX_BACKGROUND_COLOR}"))
        val ob = s.indexOf("openbox >>")
        val tint = s.indexOf("tint2 >>")
        val term = s.indexOf("xterm -geometry")
        assertTrue(s.indexOf("xsetroot") in 0 until ob)
        assertTrue(ob in 0 until tint && tint < term)
        assertFalse(s.contains("xfwm4"))
    }

    @Test
    fun `xfce session starts the xfce components and no openbox`() {
        val s = DesktopFlavors.sessionCommands(DesktopFlavor.XFCE)
        listOf("xfwm4 >>", "xfdesktop >>", "xfce4-panel >>", "xfce4-terminal").forEach {
            assertTrue("missing $it", s.contains(it))
        }
        assertFalse(s.contains("openbox"))
    }

    @Test
    fun `required binaries follow the flavour`() {
        assertEquals(DesktopCommand.REQUIRED_BINARIES, DesktopFlavors.requiredBinaries(DesktopFlavor.OPENBOX))
        val xfce = DesktopFlavors.requiredBinaries(DesktopFlavor.XFCE)
        assertTrue(xfce.containsAll(listOf("Xvfb", "xfwm4", "xfdesktop", "xfce4-panel")))
        assertFalse("openbox" in xfce)
    }

    @Test
    fun `alpine series and repositories file`() {
        assertEquals("3.24", DesktopFlavors.alpineSeries("3.24.3"))
        assertEquals("3.24", DesktopFlavors.alpineSeries("3.24"))
        assertEquals("edge", DesktopFlavors.alpineSeries("edge"))
        assertEquals(
            "https://m/alpine/v3.24/main\nhttps://m/alpine/v3.24/community\n",
            DesktopFlavors.repositoriesFile("3.24.3", "https://m/alpine/"),
        )
    }

    @Test
    fun `ensure repositories command is idempotent grep or append`() {
        val cmd = DesktopFlavors.ensureRepositoriesCommand("3.24.3", "https://m/alpine")
        assertTrue(cmd.contains("grep -q \"/v3.24/community\""))
        assertTrue(cmd.contains("|| echo \"https://m/alpine/v3.24/community\" >>"))
    }

    @Test
    fun `firefox wrapper disables sandboxes and prefers firefox-esr`() {
        val w = FirefoxLauncher.wrapperScript()
        assertTrue(w.startsWith("#!/bin/sh\n"))
        listOf(
            FirefoxLauncher.ENV_FAKE_NO_SANDBOX,
            FirefoxLauncher.ENV_DISABLE_CONTENT_SANDBOX,
            FirefoxLauncher.ENV_DISABLE_GMP_SANDBOX,
        ).forEach { assertTrue(w.contains("export $it=1")) }
        assertTrue(w.indexOf("exec firefox-esr") < w.lastIndexOf("exec firefox \"\$@\""))
    }

    @Test
    fun `firefox install command writes the quoted wrapper and makes it executable`() {
        val c = FirefoxLauncher.installCommand()
        assertTrue(c.contains(ShellQuote.quote(FirefoxLauncher.wrapperScript())))
        assertTrue(c.contains("> ${FirefoxLauncher.GUEST_WRAPPER_PATH}"))
        assertTrue(c.endsWith("chmod 0755 ${FirefoxLauncher.GUEST_WRAPPER_PATH}"))
    }
}
