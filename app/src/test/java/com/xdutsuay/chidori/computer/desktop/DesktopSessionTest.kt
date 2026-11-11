package com.xdutsuay.chidori.computer.desktop

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xdutsuay.chidori.computer.ComputerFlagStore
import com.xdutsuay.chidori.computer.CosConstants
import com.xdutsuay.chidori.computer.CosFiles
import com.xdutsuay.chidori.computer.CosProvisioning
import com.xdutsuay.chidori.computer.CosRootfsBootstrap
import com.xdutsuay.chidori.computer.LocalComputerRuntime
import com.xdutsuay.chidori.computer.ProcTree
import com.xdutsuay.chidori.computer.ProotCommand
import com.xdutsuay.chidori.computer.ProotTerminator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop runtime on the JVM with a fake `libproot.so` (shell script, same pattern as
 * ProotTerminationTest). In "desktop" mode (the argv carries the launch script) the fake
 * starts a guest child (`sleep`), prints the READY marker and waits, with proot's SIGQUIT
 * handler emulated (or ignored). Proves: one supervised tree, Stop kills the whole tree
 * (also when proot ignores SIGQUIT), state transitions, `execInDesktop` argv/env through
 * execStreaming, chat-Stop cancellation, and that [DesktopBridges.bridge] is published.
 *
 * Unix-only: the fake is a shebang script executed as `libproot.so`, and the suite reads
 * `/proc` + `kill` for tree membership. On Windows those paths fail with CreateProcess
 * 193; run on Linux CI / the CP2 box (or WSL) instead.
 */
@RunWith(RobolectricTestRunner::class)
class DesktopSessionTest {
    private lateinit var context: Context
    private lateinit var flags: ComputerFlagStore
    private lateinit var tmp: File
    private lateinit var libDir: File
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val payload = "fake alpine 3.24.3 tarball".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val config = DesktopConfig(display = 42)

    private fun requireUnixHost() = assumeFalse(
        "fake libproot.so shell + /proc/kill need a Unix host",
        System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        flags = ComputerFlagStore.fromSharedPreferences(prefs)
        flags.setComputerEnabled(true)
        CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        tmp = Files.createTempDirectory("fakelib-desktop").toFile()
        libDir = File(tmp, "lib/x86_64").apply { mkdirs() }
        ProotCommand.REQUIRED_LIBS.forEach { File(libDir, it).writeText("") }
        File(libDir, ProotCommand.PROOT_LIB).apply {
            writeText(FAKE_DESKTOP_PROOT)
            setExecutable(true)
        }
    }

    @After
    fun tearDown() {
        pidFile("child.pid")?.let { if (alive(it)) ProcessBuilder("kill", "-9", "$it").start().waitFor() }
        pidFile("exec_child.pid")?.let { if (alive(it)) ProcessBuilder("kill", "-9", "$it").start().waitFor() }
        DesktopBridges.bridge = null
        CosFiles.deleteTree(tmp)
        CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
    }

    private fun runtime() = LocalComputerRuntime(
        context = context,
        flags = flags,
        bootstrap = CosRootfsBootstrap(
            fetch = { _, dest, _ -> dest.writeBytes(payload); CosProvisioning.Outcome(true, "ok") },
            extract = { _, dest ->
                File(dest, "bin").mkdirs()
                File(dest, "etc").mkdirs()
                File(dest, "etc/alpine-release").writeText("3.24.3\n")
                CosProvisioning.Outcome(true, "extracted")
            },
            expectedSha256For = { payloadSha },
        ),
        nativeLibDirOverride = libDir.absolutePath,
        log = { logs += it },
        killGraceMs = 800,
        // Robolectric's Os.kill is a shadow; deliver real signals so the tree really dies.
        sendSignal = { pid, sig -> ProcessBuilder("kill", "-$sig", "$pid").start().waitFor() },
    )

    private fun installedBridge(): Pair<LocalComputerRuntime, LocalDesktopBridge> {
        val rt = runtime()
        runBlocking { rt.ensureRootfs() }
        // Pretend the pinned default set was installed (apk itself is covered by the box smoke run).
        val set = DesktopPackages.defaultSet("x86_64")!!
        File(rt.hostRootfsDir, DesktopCommand.GUEST_INSTALLED_MARKER.trimStart('/')).apply {
            parentFile!!.mkdirs()
            writeText("${set.id} ${set.arch} ${set.apkAddArgs.joinToString(" ")}\n")
        }
        return rt to LocalDesktopBridge(rt, config, log = { logs += it }, readyTimeoutMs = 10_000)
    }

    private fun pidFile(name: String): Int? = File(libDir, name).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    private fun alive(pid: Int): Boolean {
        val stat = ProcTree.read(File("/proc"), pid) ?: return false
        return stat.state != 'Z' && stat.state != 'X'
    }

    private fun waitFor(name: String): Int {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            pidFile(name)?.let { return it }
            Thread.sleep(50)
        }
        error("$name never written; logs=$logs")
    }

    @Test
    fun `not installed until the marker matches the pins`() {
        val rt = runtime()
        runBlocking { rt.ensureRootfs() }
        val b = LocalDesktopBridge(rt, config, log = { logs += it })
        assertEquals(DesktopState.NotInstalled, b.state.value)
        val r = runBlocking { b.start() }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("not installed"))
        File(rt.hostRootfsDir, DesktopCommand.GUEST_INSTALLED_MARKER.trimStart('/')).apply {
            parentFile!!.mkdirs()
            writeText("default x86_64 xvfb=0.0-r0\n") // stale pins
        }
        b.refresh()
        assertFalse(b.isInstalled())
        assertEquals(DesktopState.NotInstalled, b.state.value)
    }

    @Test
    fun `start reaches Running and stop kills the whole proot tree`() = runBlocking {
        requireUnixHost()
        val (_, b) = installedBridge()
        assertEquals(DesktopState.Stopped, b.state.value)
        val running = b.start().getOrThrow()
        assertEquals(":42", running.display)
        assertEquals(1280 to 720, running.width to running.height)
        assertEquals(DesktopCommand.GUEST_XAUTHORITY, running.xauthorityGuestPath)
        assertTrue(running.framebufferHostFile.path.endsWith("var/lib/cos-desktop/fb/Xvfb_screen0"))
        assertNull(running.vncSocketHostPath)
        assertTrue(b.isDesktopRunning())
        assertEquals(running, b.start().getOrThrow()) // idempotent, no second tree
        assertEquals(1, File(libDir, "desktop_starts").readText().trim().toInt())
        val child = waitFor("child.pid")
        assertTrue(alive(child))
        b.stop()
        assertEquals(DesktopState.Stopped, b.state.value)
        assertTrue(File(libDir, "events").readText().contains("QUIT"))
        Thread.sleep(200)
        assertFalse("guest child $child survived", alive(child))
        assertTrue(logs.toString(), logs.any { it.startsWith("desktop ready display=:42 pid=") })
        assertTrue(logs.toString(), logs.any { it.matches(Regex("desktop stop reason=desktop-stop display=:42 tree=\\d+")) })
        assertTrue(logs.toString(), logs.contains("desktop stopped display=:42 survivors=0"))
    }

    @Test
    fun `stop force-kills and sweeps when proot ignores SIGQUIT`() = runBlocking {
        requireUnixHost()
        File(libDir, "ignore_quit").writeText("1")
        val (_, b) = installedBridge()
        b.start().getOrThrow()
        val child = waitFor("child.pid")
        b.stop()
        Thread.sleep(200)
        assertFalse("guest child $child survived", alive(child))
        assertTrue(logs.toString(), logs.any { it.contains("destroyForcibly") })
        assertTrue(logs.toString(), logs.contains("desktop stopped display=:42 survivors=0"))
        assertEquals(DesktopState.Stopped, b.state.value)
    }

    @Test
    fun `guest error marker fails the start with its message`() = runBlocking {
        requireUnixHost()
        File(libDir, "desktop_error").writeText("1")
        val (_, b) = installedBridge()
        val r = b.start()
        assertTrue(r.isFailure)
        val s = b.state.value as DesktopState.Error
        assertTrue(s.message, s.message.contains("already served by another X server"))
        assertTrue(logs.any { it.startsWith("desktop error: display :42 already served") })
    }

    @Test
    fun `unexpected session exit moves Running to Error`() = runBlocking {
        requireUnixHost()
        val (_, b) = installedBridge()
        b.start().getOrThrow()
        val child = waitFor("child.pid")
        ProcessBuilder("kill", "-9", "$child").start().waitFor() // Xvfb "crashes"
        val deadline = System.currentTimeMillis() + 5_000
        while (b.state.value !is DesktopState.Error && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("state=${b.state.value}", b.state.value is DesktopState.Error)
        assertTrue(logs.any { it.startsWith("desktop exited unexpectedly") })
        assertFalse(b.isDesktopRunning())
        // Restart from Error works.
        assertTrue(b.start().isSuccess)
        b.stop()
    }

    @Test
    fun `execInDesktop runs sh -c with DISPLAY XAUTHORITY HOME via execStreaming`() = runBlocking {
        requireUnixHost()
        val (_, b) = installedBridge()
        assertEquals(127, b.execInDesktop("xdotool getmouselocation").exitCode)
        b.start().getOrThrow()
        val cmd = "xdotool 'mousemove' '10' '20'"
        val r = b.execInDesktop(cmd, 5.seconds)
        assertEquals(0, r.exitCode)
        val args = r.stdout.lines().filter { it.startsWith("ARG:") }.map { it.removePrefix("ARG:") }
        val tail = args.takeLast(3)
        assertEquals("/bin/sh", tail[0])
        assertEquals("-c", tail[1])
        assertEquals("export DISPLAY=:42 XAUTHORITY=/var/lib/cos-desktop/Xauthority HOME=/root; $cmd", tail[2])
        assertTrue(logs.any { it.startsWith("exec path=proot") })
        b.stop()
    }

    @Test
    fun `chat Stop cancels execInDesktop through ProotTerminator`() = runBlocking {
        requireUnixHost()
        val (_, b) = installedBridge()
        b.start().getOrThrow()
        File(libDir, "exec_hang").writeText("1")
        val job = async(Dispatchers.IO, start = CoroutineStart.LAZY) { b.execInDesktop("xdotool sleep 300", 60.seconds) }
        job.start()
        val child = waitFor("exec_child.pid")
        val t0 = System.currentTimeMillis()
        job.cancel(CancellationException("chat-stop"))
        runCatching { job.await() }
        Thread.sleep(300)
        assertFalse("exec child $child survived", alive(child))
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
        assertTrue(logs.toString(), logs.any { it.contains("terminate reason=desktop-exec") })
        assertTrue("desktop must survive a cancelled exec", b.isDesktopRunning())
        b.stop()
    }

    @Test
    fun `frame decodes the fbdir file of the running session`() = runBlocking {
        requireUnixHost()
        val (rt, b) = installedBridge()
        assertNull(b.frame())
        b.start().getOrThrow()
        val fb = File(rt.hostRootfsDir, DesktopCommand.GUEST_FB_FILE.trimStart('/'))
        fb.parentFile!!.mkdirs()
        fb.writeBytes(GZIPInputStream(javaClass.getResourceAsStream("/xwd/xvfb-fbdir-160x120.xwd.gz")!!).use { it.readBytes() })
        val img = b.frame()
        assertNotNull(img)
        assertEquals(160 to 120, img!!.width to img.height)
        b.stop()
    }

    @Test
    fun `get publishes the process-wide bridge for the chat tools`() {
        DesktopBridges.bridge = null
        val b = LocalDesktopBridge.get(context)
        assertTrue(DesktopBridges.bridge === b)
        assertTrue(LocalDesktopBridge.get(context) === b)
    }

    companion object {
        val FAKE_DESKTOP_PROOT = """
            #!/bin/sh
            d="${'$'}(dirname "${'$'}0")"
            case "${'$'}*" in
            *COS-DESKTOP-READY*)
              n=${'$'}(cat "${'$'}d/desktop_starts" 2>/dev/null || echo 0)
              echo ${'$'}((n+1)) > "${'$'}d/desktop_starts"
              if [ -f "${'$'}d/desktop_error" ]; then
                echo "COS-DESKTOP-ERROR display :42 already served by another X server; pick another display"
                exit 1
              fi
              if [ -f "${'$'}d/ignore_quit" ]; then
                trap '' QUIT
              else
                trap 'echo QUIT >> "${'$'}d/events"; kill -9 ${'$'}c 2>/dev/null; exit 131' QUIT
              fi
              sleep 300 >/dev/null 2>&1 &
              c=${'$'}!
              echo ${'$'}c > "${'$'}d/child.pid"
              echo "COS-DESKTOP-READY display=:42 width=1280 height=720 depth=24"
              wait ${'$'}c
              echo "COS-DESKTOP-EXIT xvfb rc=${'$'}?"
              exit 1
              ;;
            esac
            for a in "${'$'}@"; do echo "ARG:${'$'}a"; done
            if [ -f "${'$'}d/exec_hang" ]; then
              trap 'kill -9 ${'$'}c 2>/dev/null; exit 131' QUIT
              sleep 300 >/dev/null 2>&1 &
              c=${'$'}!
              echo ${'$'}c > "${'$'}d/exec_child.pid"
              wait ${'$'}c
            fi
            exit 0
        """.trimIndent() + "\n"
    }
}
