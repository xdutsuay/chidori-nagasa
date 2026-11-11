package com.xdutsuay.chidori.computer.desktop

import com.xdutsuay.chidori.computer.ComputerStorageBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopPackagesTest {

    @Test
    fun `default set pins exactly the requested packages on both arches`() {
        for (arch in DesktopPackages.ARCHES) {
            val set = DesktopPackages.defaultSet(arch)!!
            assertEquals(DesktopPackages.DEFAULT_PACKAGES, set.pins.keys.toList())
            assertEquals("21.1.25-r0", set.pins["xvfb"])
            assertEquals("3.6.1-r8", set.pins["openbox"])
            assertEquals("17.1.3-r1", set.pins["tint2"])
            assertEquals("410-r0", set.pins["xterm"])
            assertEquals("4.20260303.1-r0", set.pins["xdotool"])
            assertEquals("1.4.0-r0", set.pins["xdpyinfo"])
            assertEquals("1.0.9-r2", set.pins["xwd"])
            assertEquals("1.1.5-r0", set.pins["xauth"])
            assertEquals("2.37-r6", set.pins["font-dejavu"])
            assertEquals("3.11-r1", set.pins["netsurf"])
            assertEquals("1.3.2-r2", set.pins["pcmanfm"])
            assertFalse("firefox must be opt-in", "firefox-esr" in set.pins)
            assertTrue(set.apkAddArgs.all { Regex("^[a-z0-9-]+=[0-9][0-9.]*-r[0-9]+$").matches(it) })
            // No VNC in v1.
            assertTrue(set.pins.keys.none { it.contains("vnc") })
        }
    }

    @Test
    fun `firefox is a separate opt-in set`() {
        for (arch in DesktopPackages.ARCHES) {
            assertEquals(mapOf("firefox-esr" to "140.12.0-r0"), DesktopPackages.firefoxSet(arch)!!.pins)
            assertEquals(listOf(DesktopPackages.SET_DEFAULT), DesktopPackages.setsFor(arch, false).map { it.id })
            assertEquals(
                listOf(DesktopPackages.SET_DEFAULT, DesktopPackages.SET_FIREFOX),
                DesktopPackages.setsFor(arch, true).map { it.id },
            )
        }
        assertTrue(DesktopPackages.setsFor("armv7", true).isEmpty())
    }

    @Test
    fun `recorded closure sizes and index hashes`() {
        val a = DesktopPackages.defaultSet("aarch64")!!
        assertEquals(140, a.closurePackages)
        assertEquals(118_354_286L, a.downloadBytes)
        assertEquals(324_693_859L, a.installedBytes)
        val x = DesktopPackages.defaultSet("x86_64")!!
        assertEquals(123_988_068L, x.downloadBytes)
        assertEquals(337_580_815L, x.installedBytes)
        assertEquals(115_763_494L, DesktopPackages.firefoxSet("aarch64")!!.downloadBytes)
        assertEquals(123_468_692L, DesktopPackages.firefoxSet("x86_64")!!.downloadBytes)
        for (arch in DesktopPackages.ARCHES) {
            val idx = DesktopPackages.APKINDEX_SHA256[arch]!!
            assertEquals(setOf("main", "community"), idx.keys)
            assertTrue(idx.values.all { Regex("^[0-9a-f]{64}$").matches(it) })
        }
        assertEquals(
            "d381e5c94d9f900c9861b454c50f6a9c7f780abdeba1314a192d34dc1304e6a1",
            DesktopPackages.APKINDEX_SHA256["aarch64"]!!["main"],
        )
    }

    @Test
    fun `size computation and formatting`() {
        val sets = DesktopPackages.setsFor("aarch64", true)
        assertEquals(118_354_286L + 115_763_494L, DesktopPackages.downloadBytes(sets))
        assertEquals(324_693_859L + 287_854_520L, DesktopPackages.installedBytes(sets))
        assertEquals("112.9 MiB", DesktopPackages.formatMiB(118_354_286L))
        assertEquals("~113 MiB", DesktopPackages.approxMiB(118_354_286L))
        assertEquals("309.7 MiB", DesktopPackages.formatMiB(324_693_859L))
        assertEquals("0.0 MiB", DesktopPackages.formatMiB(0))
        // Default desktop + minirootfs stays under the 512 MiB COS soft cap; Firefox does not.
        assertTrue(DesktopPackages.installedBytes(DesktopPackages.setsFor("aarch64", false)) < ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES)
        assertTrue(DesktopPackages.installedBytes(sets) > ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES)
    }

    @Test
    fun `verify finds missing and mismatched pins`() {
        val pins = DesktopPackages.defaultSet("x86_64")!!.pins
        val all = pins.map { (n, v) -> "$n-$v" } + listOf("musl-1.2.6-r0", "font-dejavu-core-2.37-r6")
        assertTrue(DesktopPackages.verify(all, pins).ok)
        val wrong = all.map { if (it.startsWith("xvfb-")) "xvfb-21.1.24-r0" else it }.filterNot { it.startsWith("xterm-") }
        val v = DesktopPackages.verify(wrong, pins)
        assertFalse(v.ok)
        assertEquals(listOf("xterm"), v.missing)
        assertEquals(mapOf("xvfb" to "21.1.24-r0"), v.mismatched)
        val text = v.describe(pins)
        assertTrue(text, text.contains("xvfb: expected 21.1.25-r0, installed 21.1.24-r0"))
        assertTrue(text, text.contains("xterm: not installed (expected 410-r0)"))
        // Names with dashes + digits, and versions with several dots.
        assertTrue(DesktopPackages.verify(listOf("xdotool-4.20260303.1-r0"), mapOf("xdotool" to "4.20260303.1-r0")).ok)
        assertTrue(DesktopPackages.verify(listOf("font-dejavu-2.37-r6\n"), mapOf("font-dejavu" to "2.37-r6")).ok)
    }

    @Test
    fun `parses apk progress lines`() {
        assertEquals(1 to 38, DesktopPackages.parseProgress("( 1/38) Installing libSvtAv1Enc (4.1.0-r0)"))
        assertEquals(140 to 140, DesktopPackages.parseProgress("(140/140) Installing pcmanfm (1.3.2-r2)"))
        assertNull(DesktopPackages.parseProgress("OK: 330.6 MiB in 155 packages"))
        assertNull(DesktopPackages.parseProgress("fetch https://dl-cdn.alpinelinux.org/..."))
        assertNull(DesktopPackages.parseProgress("(5/0) x"))
    }

    @Test
    fun `failure hint names stale pins`() {
        val out = "ERROR: unable to select packages:\n  xvfb-21.1.26-r0:\n    breaks: world[xvfb=21.1.25-r0]"
        assertTrue(DesktopPackages.failureHint(out).contains("pins must be bumped"))
        assertEquals("", DesktopPackages.failureHint("something else"))
    }

    @Test
    fun `index snapshot report compares cached indexes`() {
        val dir = Files.createTempDirectory("apkcache").toFile()
        try {
            assertTrue(DesktopPackages.indexSnapshotReport(dir, "x86_64").contains("no cached index"))
            File(dir, "APKINDEX.deadbeef.tar.gz").writeText("not the real index")
            val r = DesktopPackages.indexSnapshotReport(dir, "x86_64")
            assertTrue(r, r.startsWith("APKINDEX snapshot differs from pinned [x86_64]"))
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                DesktopPackages.sha256(File(dir, "empty").apply { writeBytes(ByteArray(0)) }),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
