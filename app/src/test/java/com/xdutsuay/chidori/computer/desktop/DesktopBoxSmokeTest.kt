package com.xdutsuay.chidori.computer.desktop

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xdutsuay.chidori.computer.ComputerFlagStore
import com.xdutsuay.chidori.computer.CosConstants
import com.xdutsuay.chidori.computer.CosFiles
import com.xdutsuay.chidori.computer.CosProvisioning
import com.xdutsuay.chidori.computer.CosRootfsBootstrap
import com.xdutsuay.chidori.computer.LocalComputerRuntime
import com.xdutsuay.chidori.computer.ProotCommand
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Manual end-to-end smoke run of the real desktop on a Linux x86_64 host (the CP2 box),
 * through the production [LocalDesktopBridge] / [DesktopSession] / proot code paths.
 * Skipped unless `COS_DESKTOP_SMOKE_ROOTFS` (an x86_64 Alpine 3.24 rootfs) and
 * `COS_DESKTOP_SMOKE_PROOT` (a host proot binary) are set. Uses display `:42`
 * (`COS_DESKTOP_SMOKE_DISPLAY`) so the host's own X displays are never touched, and
 * writes the frame + log to `COS_DESKTOP_SMOKE_OUT`.
 *
 * Host proot (proot-me) has no `-L` and no loader lib, so `libproot.so` here is a
 * wrapper that drops `-L` and `PROOT_LOADER` and execs the host proot. It also drops
 * `--link2symlink`: proot-me's emulation makes `apk add` fail on the box ("failed to write
 * database: Operation not permitted"), while the box FS allows real hard links. The device
 * (Termux proot, Android FS without link()) keeps the flag.
 */
@RunWith(RobolectricTestRunner::class)
class DesktopBoxSmokeTest {
    private val rootfsSrc = System.getenv("COS_DESKTOP_SMOKE_ROOTFS")
    private val hostProot = System.getenv("COS_DESKTOP_SMOKE_PROOT")
    private val outDir = File(System.getenv("COS_DESKTOP_SMOKE_OUT") ?: "build/cos-desktop-smoke")
    private val display = System.getenv("COS_DESKTOP_SMOKE_DISPLAY")?.toInt() ?: 42
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var tmp: File? = null

    private fun log(msg: String) {
        val line = "${System.currentTimeMillis()} $msg"
        logs += line
        println("SMOKE $line")
    }

    @After
    fun tearDown() {
        tmp?.let { CosFiles.deleteTree(it) }
        runCatching {
            val context: Context = ApplicationProvider.getApplicationContext()
            CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        }
    }

    @Test
    fun `real desktop on the box - install verify, start, exec, frame, stop leaves nothing`() = runBlocking {
        assumeTrue("set COS_DESKTOP_SMOKE_ROOTFS / COS_DESKTOP_SMOKE_PROOT", rootfsSrc != null && hostProot != null)
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val flags = ComputerFlagStore.fromSharedPreferences(prefs).apply { setComputerEnabled(true) }
        CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        val t = Files.createTempDirectory("cos-desktop-smoke").toFile().also { tmp = it }
        val libDir = File(t, "lib/x86_64").apply { mkdirs() }
        ProotCommand.REQUIRED_LIBS.forEach { File(libDir, it).writeText("") }
        File(libDir, ProotCommand.PROOT_LIB).apply {
            writeText(
                """
                |#!/bin/sh
                |unset PROOT_LOADER
                |n=${'$'}#; i=0
                |while [ ${'$'}i -lt ${'$'}n ]; do a="${'$'}1"; shift; i=${'$'}((i+1)); case "${'$'}a" in -L|--link2symlink) ;; *) set -- "${'$'}@" "${'$'}a";; esac; done
                |exec $hostProot "${'$'}@"
                |""".trimMargin(),
            )
            setExecutable(true)
        }
        val payload = "box smoke rootfs".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val rt = LocalComputerRuntime(
            context = context,
            flags = flags,
            bootstrap = CosRootfsBootstrap(
                fetch = { _, dest, _ -> dest.writeBytes(payload); CosProvisioning.Outcome(true, "ok") },
                extract = { _, dest ->
                    dest.mkdirs()
                    val rc = ProcessBuilder("cp", "-a", "$rootfsSrc/.", dest.absolutePath).inheritIO().start().waitFor()
                    CosProvisioning.Outcome(rc == 0, "cp rc=$rc")
                },
                expectedSha256For = { sha },
            ),
            nativeLibDirOverride = libDir.absolutePath,
            log = { log("COS: $it") },
            sendSignal = { pid, sig -> ProcessBuilder("kill", "-$sig", "$pid").start().waitFor() },
        )
        log("ensureRootfs -> ${rt.ensureRootfs()}")
        val bridge = LocalDesktopBridge(rt, DesktopConfig(display = display), log = { log("COS: $it") })
        log("state0=${bridge.state.value}")

        val installed = bridge.install()
        log("install -> $installed")
        assertEquals(DesktopState.Stopped, installed)

        val t0 = System.currentTimeMillis()
        val running = bridge.start().getOrThrow()
        log("start -> $running in ${System.currentTimeMillis() - t0}ms")
        val tree = treeOf(prootPids(rt.hostRootfsDir.absolutePath))
        log("tree=" + tree.joinToString { "${it.first}:${it.second}" })
        val comms = tree.map { it.second }.toSet()
        for (c in listOf("Xvfb", "openbox", "tint2", "xterm")) assertTrue("$c missing in $comms", c in comms)

        val geo = bridge.execInDesktop("xdotool getdisplaygeometry", 20.seconds)
        log("exec xdotool getdisplaygeometry -> rc=${geo.exitCode} out=${geo.stdout.trim()} err=${geo.stderr.trim()}")
        assertEquals("1280 720", geo.stdout.trim())
        val info = bridge.execInDesktop("xdpyinfo | grep -E 'name of display|dimensions|depths' ; echo DISPLAY=\$DISPLAY HOME=\$HOME", 20.seconds)
        log("exec xdpyinfo -> rc=${info.exitCode}\n${info.stdout}")
        val noCookie = bridge.execInDesktop("XAUTHORITY=/nonexistent xdpyinfo >/dev/null 2>&1; echo rc=\$?", 20.seconds)
        log("exec without cookie -> ${noCookie.stdout.trim()}")
        assertTrue(noCookie.stdout.trim() != "rc=0")

        val unix = File("/proc/net/unix").readLines().filter { it.contains("X$display") }
        log("host /proc/net/unix X$display entries: ${unix.size} ${unix.map { it.substringAfterLast(' ') }}")
        assertTrue("abstract socket exposed: $unix", unix.none { it.endsWith("@/tmp/.X11-unix/X$display") })
        val tcpPort = "%04X".format(6000 + display)
        val tcp = (File("/proc/net/tcp").readLines() + File("/proc/net/tcp6").readLines())
            .filter { it.trim().split(Regex("\\s+")).getOrNull(1)?.endsWith(":$tcpPort") == true }
        log("host tcp listeners on ${6000 + display}: ${tcp.size}")
        assertTrue(tcp.isEmpty())

        Thread.sleep(3_000) // let openbox / tint2 / xterm paint
        val img = bridge.frame()!!
        val colors = img.argb.toHashSet().size
        log("frame ${img.width}x${img.height} distinct_colors=$colors")
        assertEquals(1280 to 720, img.width to img.height)
        assertTrue("frame looks blank ($colors colors)", colors > 8)
        outDir.mkdirs()
        val png = File(outDir, "cp2-m1-smoke-x86_64-display$display.png")
        png.writeBytes(pngRgb(img.width, img.height, img.argb))
        File(outDir, "cp2-m1-smoke-x86_64-display$display.xwd").writeBytes(running.framebufferHostFile.readBytes())
        log("wrote ${png.absolutePath} ${png.length()} bytes")

        bridge.stop()
        log("state after stop=${bridge.state.value}")
        Thread.sleep(500)
        val left = tree.filter { File("/proc/${it.first}").exists() && readComm(it.first) == it.second }
        log("survivors by pid: $left")
        assertTrue(left.isEmpty())
        assertTrue(prootPids(rt.hostRootfsDir.absolutePath).isEmpty())
        assertTrue(logs.any { it.endsWith("COS: desktop stopped display=:$display survivors=0") })
        assertEquals(DesktopState.Stopped, bridge.state.value)
        File(outDir, "cp2-m1-smoke-x86_64-display$display.log").writeText(logs.joinToString("\n") + "\n")
    }

    /** Minimal RGB PNG (android.graphics / javax.imageio are unavailable in this JVM test). */
    private fun pngRgb(w: Int, h: Int, argb: IntArray): ByteArray {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val row = ByteArray(1 + w * 3)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = argb[y * w + x]
                    row[1 + x * 3] = (p shr 16).toByte(); row[2 + x * 3] = (p shr 8).toByte(); row[3 + x * 3] = p.toByte()
                }
                z.write(row)
            }
        }
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size)
            val td = type.toByteArray() + data
            d.write(td)
            d.writeInt(CRC32().apply { update(td) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", raw.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun readComm(pid: Int) = runCatching { File("/proc/$pid/comm").readText().trim() }.getOrDefault("")

    private fun prootPids(rootfs: String): List<Int> = File("/proc").listFiles().orEmpty()
        .mapNotNull { it.name.toIntOrNull() }
        .filter { pid ->
            val cmd = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")
            cmd.contains("proot") && cmd.contains(rootfs)
        }

    /** (pid, comm) of [roots] and all descendants. */
    private fun treeOf(roots: List<Int>): List<Pair<Int, String>> {
        val parent = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.mapNotNull { pid ->
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return@mapNotNull null
            val ppid = stat.substringAfterLast(')').trim().split(' ')[1].toInt()
            pid to ppid
        }
        val out = LinkedHashSet<Int>(roots)
        var grew = true
        while (grew) {
            grew = false
            for ((pid, ppid) in parent) if (ppid in out && out.add(pid)) grew = true
        }
        return out.map { it to readComm(it) }
    }
}
