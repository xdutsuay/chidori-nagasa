package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosProcStatsTest {

    // ------------------------------------------------------------------
    // parseUid
    // ------------------------------------------------------------------

    @Test
    fun `parseUid reads real uid from status line`() {
        val status = "Name:\tproot\nUid:\t10352\t10352\t10352\t10352\nGid:\t10352\t10352\n"
        assertEquals(10352, CosProcStats.parseUid(status))
    }

    @Test
    fun `parseUid accepts space separated fields`() {
        assertEquals(0, CosProcStats.parseUid("Uid:\t0\t0\t0\t0"))
        assertEquals(10023, CosProcStats.parseUid("Uid: 10023 10023 10023 10023\n"))
    }

    @Test
    fun `parseUid returns null when Uid line missing`() {
        assertNull(CosProcStats.parseUid("Name:\tproot\nGid:\t100\n"))
        assertNull(CosProcStats.parseUid(""))
    }

    @Test
    fun `parseUid returns null when value unparsable`() {
        assertNull(CosProcStats.parseUid("Uid:\tabc\tdef\n"))
        assertNull(CosProcStats.parseUid("Uid:\n"))
    }

    // ------------------------------------------------------------------
    // parseCpuTicks
    // ------------------------------------------------------------------

    @Test
    fun `parseCpuTicks sums utime and stime for simple comm`() {
        val stat = "42 (bash) R 1 1 1 0 1 4194304 100 0 0 0 7 3 0 0 0 0 0 0"
        assertEquals(10L, CosProcStats.parseCpuTicks(stat))
    }

    @Test
    fun `parseCpuTicks handles comm containing spaces and parens`() {
        // comm is "(Xvfb :1 (x))" — must parse after the LAST ')'.
        val stat = "1337 (Xvfb :1 (x)) S 1 2 3 0 -1 4194304 120 0 5 0 12 4 0 0 0 0 0 0"
        assertEquals(16L, CosProcStats.parseCpuTicks(stat))
    }

    @Test
    fun `parseCpuTicks returns null for missing closing paren`() {
        assertNull(CosProcStats.parseCpuTicks("42 (bash R 1 1 1"))
        assertNull(CosProcStats.parseCpuTicks("42 bash R 1 1 1"))
    }

    @Test
    fun `parseCpuTicks returns null when too short`() {
        // Only state after last ')' — utime/stime fields missing.
        assertNull(CosProcStats.parseCpuTicks("42 (bash) R"))
        assertNull(CosProcStats.parseCpuTicks(""))
    }

    @Test
    fun `parseCpuTicks returns null when fields not numeric`() {
        assertNull(
            CosProcStats.parseCpuTicks("42 (bash) R 1 1 1 0 1 0 0 0 0 0 xx 3 0 0 0"),
        )
    }

    @Test
    fun `parseCpuTicks handles zero ticks`() {
        val stat = "9 (sleep) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0"
        assertEquals(0L, CosProcStats.parseCpuTicks(stat))
    }

    // ------------------------------------------------------------------
    // parseRssPages
    // ------------------------------------------------------------------

    @Test
    fun `parseRssPages reads second token`() {
        assertEquals(567L, CosProcStats.parseRssPages("1234 567 89"))
    }

    @Test
    fun `parseRssPages reads zero resident pages`() {
        assertEquals(0L, CosProcStats.parseRssPages("100 0 5"))
    }

    @Test
    fun `parseRssPages returns null when resident field missing`() {
        assertNull(CosProcStats.parseRssPages("1234"))
        assertNull(CosProcStats.parseRssPages(""))
        assertNull(CosProcStats.parseRssPages("   "))
    }

    @Test
    fun `parseRssPages returns null when not numeric`() {
        assertNull(CosProcStats.parseRssPages("1234 abc 89"))
    }

    // ------------------------------------------------------------------
    // isCosProcess
    // ------------------------------------------------------------------

    private val pkg = "com.druk.lmplayground"

    @Test
    fun `isCosProcess rejects the app process itself by pid`() {
        assertFalse(
            CosProcStats.isCosProcess(
                4242,
                "$pkg\u0000--serve",
                appPid = 4242,
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `isCosProcess rejects blank cmdline`() {
        assertFalse(CosProcStats.isCosProcess(4243, "", appPid = 4242, packageName = pkg))
        assertFalse(CosProcStats.isCosProcess(4243, "   ", appPid = 4242, packageName = pkg))
    }

    @Test
    fun `isCosProcess rejects app main process cmdline`() {
        assertFalse(
            CosProcStats.isCosProcess(
                4244,
                "$pkg\u0000",
                appPid = 4242,
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `isCosProcess rejects llama service process`() {
        assertFalse(
            CosProcStats.isCosProcess(
                4245,
                "$pkg:llama\u0000",
                appPid = 4242,
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `isCosProcess rejects any other package service process`() {
        assertFalse(
            CosProcStats.isCosProcess(
                4246,
                "$pkg:downloader\u0000--flag",
                appPid = 4242,
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `isCosProcess accepts proot child`() {
        assertTrue(
            CosProcStats.isCosProcess(
                4247,
                "/data/data/$pkg/files/proot/libproot.so\u0000--rootfs=/data\u0000/bin/sh",
                appPid = 4242,
                packageName = pkg,
            ),
        )
    }

    @Test
    fun `isCosProcess accepts Xvfb child`() {
        assertTrue(
            CosProcStats.isCosProcess(4248, "Xvfb\u0000:1", appPid = 4242, packageName = pkg),
        )
    }

    @Test
    fun `isCosProcess rejects by pid before consulting cmdline`() {
        assertFalse(
            CosProcStats.isCosProcess(
                4242,
                "Xvfb\u0000:1",
                appPid = 4242,
                packageName = pkg,
            ),
        )
    }

    // ------------------------------------------------------------------
    // cpuPercent
    // ------------------------------------------------------------------

    private fun assertPercent(expected: Double, actual: Double?) {
        assertEquals(expected, actual!!, 1e-9)
    }

    @Test
    fun `cpuPercent full single core`() {
        assertPercent(100.0, CosProcStats.cpuPercent(0, 100, 1000, 1))
    }

    @Test
    fun `cpuPercent divides across cores`() {
        assertPercent(25.0, CosProcStats.cpuPercent(0, 50, 1000, 2))
    }

    @Test
    fun `cpuPercent clamps above 100`() {
        assertPercent(100.0, CosProcStats.cpuPercent(0, 400, 1000, 1))
    }

    @Test
    fun `cpuPercent zero when ticks unchanged`() {
        assertPercent(0.0, CosProcStats.cpuPercent(10, 10, 2000, 8))
    }

    @Test
    fun `cpuPercent uses explicit clock ticks per second`() {
        // 200 ticks over 1s on 2 cores with 200 ticks/s: (200/200)/(1.0)/2*100 = 50
        assertPercent(50.0, CosProcStats.cpuPercent(0, 200, 1000, 2, clockTicksPerSec = 200))
    }

    @Test
    fun `cpuPercent null on invalid elapsed`() {
        assertNull(CosProcStats.cpuPercent(0, 100, 0, 1))
        assertNull(CosProcStats.cpuPercent(0, 100, -5, 1))
    }

    @Test
    fun `cpuPercent null on invalid cores or clock`() {
        assertNull(CosProcStats.cpuPercent(0, 100, 1000, 0))
        assertNull(CosProcStats.cpuPercent(0, 100, 1000, -2))
        assertNull(CosProcStats.cpuPercent(0, 100, 1000, 1, clockTicksPerSec = 0))
    }

    @Test
    fun `cpuPercent null when ticks go backwards`() {
        assertNull(CosProcStats.cpuPercent(100, 50, 1000, 1))
    }
}
