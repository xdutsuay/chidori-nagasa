package com.xdutsuay.chidori.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCommandTest {

    @Test
    fun `xvfb argv has the security flags`() {
        val argv = DesktopCommand.xvfbArgv(DesktopConfig())
        assertEquals("Xvfb", argv[0])
        assertEquals(":1", argv[1])
        fun pairs(flag: String) = argv.indices.filter { argv[it] == flag }.map { argv[it + 1] }
        assertEquals(listOf("tcp", "local"), pairs("-nolisten"))
        assertEquals(listOf(DesktopCommand.GUEST_XAUTHORITY), pairs("-auth"))
        assertEquals(listOf(DesktopCommand.GUEST_FB_DIR), pairs("-fbdir"))
        assertEquals(listOf("0", "1280x720x24"), argv.subList(argv.indexOf("-screen") + 1, argv.indexOf("-screen") + 3))
        assertFalse(argv.any { it.contains("listen tcp") || it == "-listen" })
        assertTrue(DesktopCommand.GUEST_FB_DIR.startsWith("/var/lib/cos-desktop"))
    }

    @Test
    fun `display comes from config`() {
        val c = DesktopConfig(display = 42, width = 640, height = 480, depth = 24)
        assertEquals(":42", DesktopCommand.xvfbArgv(c)[1])
        val script = DesktopCommand.launchScript(c)
        assertTrue(script.contains("N=42"))
        assertTrue(script.contains("'Xvfb' ':42' '-screen' '0' '640x480x24' '-nolisten' 'tcp' '-nolisten' 'local' '-auth'"))
        assertTrue(DesktopCommand.execArgv(c, "xdotool getactivewindow").last()
            .startsWith("export DISPLAY=:42 XAUTHORITY=/var/lib/cos-desktop/Xauthority HOME=/root; xdotool"))
        for (bad in listOf(-1, 1000)) {
            assertTrue(runCatching { DesktopConfig(display = bad) }.isFailure)
        }
        assertTrue(runCatching { DesktopConfig(depth = 32) }.isFailure)
    }

    @Test
    fun `launch script order and hygiene`() {
        val s = DesktopCommand.launchScript(DesktopConfig())
        fun at(needle: String) = s.indexOf(needle).also { assertTrue("missing: $needle", it >= 0) }
        // stale cleanup -> collision precheck -> cookie -> Xvfb -> readiness -> clients -> READY -> wait
        val stale = at("rm -f /tmp/.X\$N-lock /tmp/.X11-unix/X\$N")
        val pre = at("XAUTHORITY=/dev/null timeout 5 xdpyinfo")
        val cookie = at("xauth -q -f \"\$XA\" source -")
        val xvfb = at("'Xvfb' ':1'")
        val poll = at("until xdpyinfo")
        val wm = at("openbox >>")
        val panel = at("tint2 >>")
        val term = at("xterm -geometry")
        val ready = at("echo \"COS-DESKTOP-READY display=:\$N width=1280 height=720 depth=24\"")
        val wait = at("wait \$XPID")
        assertTrue(stale < pre && pre < cookie && cookie < xvfb && xvfb < poll && poll < wm)
        assertTrue(wm < panel && panel < term && term < ready && ready < wait)
        // Cookie never appears in an argv: it is generated in-shell and fed via stdin.
        assertTrue(s.contains("umask 077"))
        assertTrue(s.contains("head -c 16 /dev/urandom"))
        assertFalse(s.contains("xauth add"))
        assertTrue(s.contains("already served by another X server"))
        DesktopCommand.REQUIRED_BINARIES.forEach { assertTrue(s.contains(it)) }
        assertEquals(listOf("/bin/sh", "-c", s), DesktopCommand.sessionArgv(DesktopConfig()))
    }

    @Test
    fun `parses markers`() {
        assertEquals(
            DesktopCommand.Ready(":1", 1280, 720, 24),
            DesktopCommand.parseReady("COS-DESKTOP-READY display=:1 width=1280 height=720 depth=24"),
        )
        assertNull(DesktopCommand.parseReady("COS-DESKTOP-READY display=/tmp/x width=1 height=1 depth=1"))
        assertNull(DesktopCommand.parseReady("[err] COS-DESKTOP-READY display=:1 width=1 height=1 depth=1"))
        assertEquals("missing Xvfb; install", DesktopCommand.parseError("COS-DESKTOP-ERROR missing Xvfb; install"))
        assertNull(DesktopCommand.parseError("COS-DESKTOP-ERRORS"))
    }
}
