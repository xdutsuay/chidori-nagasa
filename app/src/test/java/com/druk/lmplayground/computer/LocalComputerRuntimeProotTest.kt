package com.druk.lmplayground.computer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * Drives [LocalComputerRuntime] end to end on the JVM with a fake `libproot.so`
 * (a shell script) in a fake nativeLibraryDir. Proves: argv/env reach proot, the
 * seccomp → PROOT_NO_SECCOMP retry + persistence, no host fallback when proot is
 * missing, version-mismatch reprovision and checksum-mismatch refusal.
 * Real proot/$ORIGIN/seccomp behaviour still needs an on-device run.
 */
@RunWith(RobolectricTestRunner::class)
class LocalComputerRuntimeProotTest {
    private lateinit var context: Context
    private lateinit var flags: ComputerFlagStore
    private lateinit var tmp: File
    private lateinit var libDir: File
    private val logs = mutableListOf<String>()
    private val payload = "fake alpine 3.24.3 tarball".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        assumeFalse(
            "fake libproot.so is a Unix shell script (master plan: skip-on-Windows)",
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
        )
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        flags = ComputerFlagStore.fromSharedPreferences(prefs)
        flags.setComputerEnabled(true)
        CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        tmp = Files.createTempDirectory("fakelib").toFile()
        libDir = File(tmp, "lib/x86_64").apply { mkdirs() }
        ProotCommand.REQUIRED_LIBS.forEach { File(libDir, it).writeText("") }
        File(libDir, ProotCommand.PROOT_LIB).apply {
            writeText(FAKE_PROOT)
            setExecutable(true)
        }
    }

    @After
    fun tearDown() {
        // assumeFalse in setUp must not trip UninitializedPropertyAccessException here
        // (JUnit turns that into TestCouldNotBeSkippedException).
        if (::tmp.isInitialized) CosFiles.deleteTree(tmp)
        if (::context.isInitialized) {
            CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        }
    }

    private fun bootstrap(sha: (String) -> String? = { payloadSha }, extractCount: IntArray = IntArray(1)) =
        CosRootfsBootstrap(
            fetch = { _, dest, _ -> dest.writeBytes(payload); CosProvisioning.Outcome(true, "ok") },
            extract = { _, dest ->
                extractCount[0]++
                File(dest, "bin").mkdirs()
                File(dest, "etc").mkdirs()
                File(dest, "etc/alpine-release").writeText("3.24.3\n")
                CosProvisioning.Outcome(true, "extracted")
            },
            expectedSha256For = sha,
        )

    private fun runtime(b: CosRootfsBootstrap = bootstrap(), lib: String = libDir.absolutePath) =
        LocalComputerRuntime(
            context = context,
            flags = flags,
            bootstrap = b,
            nativeLibDirOverride = lib,
            log = { logs += it },
        )

    private val rootfs get() = File(context.filesDir, "cos/rootfs")
    private fun calls(): Int = File(libDir, "calls").takeIf { it.isFile }?.readText()?.trim()?.toInt() ?: 0
    private fun args(out: String) = out.lines().filter { it.startsWith("ARG:") }.map { it.removePrefix("ARG:") }
    private fun env(out: String, k: String) = out.lines().firstOrNull { it.startsWith("ENV:$k=") }?.removePrefix("ENV:$k=")

    @Test
    fun `arch comes from the installed ABI dir`() {
        assertEquals("x86_64", runtime().alpineArch())
        val arm = File(tmp, "lib/arm64").apply { mkdirs() }
        assertEquals("aarch64", runtime(lib = arm.absolutePath).alpineArch())
    }

    @Test
    fun `provision writes marker resolv conf and guest dirs`() = runTest {
        val rt = runtime()
        assertEquals(ComputerStatus.Ready, rt.ensureRootfs())
        val marker = File(rootfs, CosConstants.READY_MARKER).readText()
        assertEquals("3.24.3", CosProvisioning.markerValue(marker, "version"))
        assertEquals("x86_64", CosProvisioning.markerValue(marker, "arch"))
        assertEquals(payloadSha, CosProvisioning.markerValue(marker, "sha256"))
        assertTrue(File(rootfs, "etc/resolv.conf").readText().contains("nameserver 1.1.1.1"))
        assertTrue(File(rootfs, "tmp").isDirectory)
        assertTrue(File(rootfs, "root").isDirectory)
        assertFalse(File(context.filesDir, "cos/rootfs.partial").exists())
    }

    @Test
    fun `exec runs proot with the 3_5 argv and env`() = runTest {
        val rt = runtime()
        rt.ensureRootfs()
        val lines = mutableListOf<String>()
        val r = rt.execStreaming(CosShellLexer.toArgv("echo hi | wc -c; ls '/a b'"), "root") { lines += it }
        assertEquals(0, r.exitCode)
        val expected = ProotCommand.buildArgv(libDir.absolutePath, rootfs.absolutePath, "/root",
            listOf("/bin/sh", "-c", "echo hi | wc -c; ls '/a b'"))
        assertEquals(expected.drop(1), args(r.stdout))
        assertEquals(File(libDir, ProotCommand.LOADER_LIB).absolutePath, env(r.stdout, "PROOT_LOADER"))
        assertEquals(File(context.cacheDir, "proot-tmp").absolutePath, env(r.stdout, "PROOT_TMP_DIR"))
        assertEquals(libDir.absolutePath, env(r.stdout, "LD_LIBRARY_PATH"))
        assertEquals("", env(r.stdout, "PROOT_NO_SECCOMP"))
        assertEquals("", env(r.stdout, "LD_PRELOAD"))
        assertEquals(1, calls())
        assertFalse(flags.isProotNoSeccomp())
        assertTrue(lines.contains("ARG:--kill-on-exit"))
        assertTrue(logs.any { it.startsWith("exec path=proot seccomp=on") })
    }

    @Test
    fun `EPERM under seccomp retries with PROOT_NO_SECCOMP and persists`() = runTest {
        File(libDir, "eperm").writeText("1")
        val rt = runtime()
        rt.ensureRootfs()
        val lines = mutableListOf<String>()
        val r = rt.execStreaming(listOf("/bin/sh", "-c", "true"), "root") { lines += it }
        assertEquals(0, r.exitCode)
        assertEquals(2, calls())
        assertEquals("1", env(r.stdout, "PROOT_NO_SECCOMP"))
        assertTrue(lines.contains("[cos] retrying with PROOT_NO_SECCOMP=1"))
        assertTrue(flags.isProotNoSeccomp())
        assertTrue(logs.any { it.contains("retrying with PROOT_NO_SECCOMP=1") })
        // Next exec goes straight to the no-seccomp path.
        val r2 = runtime().execStreaming(listOf("/bin/sh", "-c", "true"), "root") { }
        assertEquals(0, r2.exitCode)
        assertEquals(3, calls())
        assertEquals("1", env(r2.stdout, "PROOT_NO_SECCOMP"))
    }

    @Test
    fun `missing proot libs refuse with 127 and never run on the host`() = runTest {
        File(libDir, ProotCommand.LOADER_LIB).delete()
        val rt = runtime()
        rt.ensureRootfs()
        val lines = mutableListOf<String>()
        val r = rt.execStreaming(listOf("/bin/sh", "-c", "echo host"), "root") { lines += it }
        assertEquals(127, r.exitCode)
        assertTrue(r.stderr.contains("proot runtime missing from APK"))
        assertEquals("", r.stdout)
        assertEquals(0, calls())
        assertTrue(lines.single().startsWith("[err] proot runtime missing"))
    }

    @Test
    fun `old 3_20 rootfs is wiped and reprovisioned`() = runTest {
        rootfs.mkdirs()
        File(rootfs, "old-file").writeText("x")
        File(rootfs, CosConstants.READY_MARKER).writeText(
            CosProvisioning.markerText("3.20.3", "x86_64", "deadbeef"),
        )
        val count = IntArray(1)
        val rt = runtime(bootstrap(extractCount = count))
        assertFalse(rt.rootfsPresent())
        assertEquals(ComputerStatus.MissingRootfs, rt.status())
        val progress = mutableListOf<String>()
        assertEquals(ComputerStatus.Ready, rt.ensureRootfsStreaming { progress += it })
        assertEquals(1, count[0])
        assertFalse(File(rootfs, "old-file").exists())
        assertEquals("3.24.3", CosProvisioning.markerValue(File(rootfs, CosConstants.READY_MARKER).readText(), "version"))
        assertTrue(progress.any { it.contains("re-provisioning") })
        assertTrue(logs.any { it.startsWith("reprovision: 3.20.3 -> 3.24.3") })
    }

    @Test
    fun `legacy offline stub marker is reprovisioned`() = runTest {
        rootfs.mkdirs()
        File(rootfs, CosConstants.READY_MARKER).writeText("channel=x\nmode=offline-stub\n")
        val rt = runtime()
        assertFalse(rt.rootfsPresent())
        assertEquals(ComputerStatus.Ready, rt.ensureRootfs())
    }

    @Test
    fun `checksum mismatch fails loudly with no rootfs left behind`() = runTest {
        // Real pinned hash vs fake bytes.
        val rt = runtime(bootstrap(sha = { CosConstants.ALPINE_SHA256[it] }))
        val st = rt.ensureRootfs()
        assertTrue(st is ComputerStatus.Error)
        assertTrue((st as ComputerStatus.Error).message.contains("checksum mismatch"))
        assertFalse(rootfs.exists())
        assertFalse(File(context.filesDir, "cos/rootfs.partial").exists())
        assertTrue(File(context.filesDir, "cos/cache").listFiles().orEmpty().isEmpty())
        assertEquals(st, rt.status())
        val r = rt.execStreaming(listOf("/bin/sh", "-c", "true")) { }
        assertEquals(127, r.exitCode)
        assertEquals(0, calls())
    }

    @Test
    fun `computer disabled refuses without touching proot`() = runTest {
        flags.setComputerEnabled(false)
        val r = runtime().execStreaming(listOf("/bin/sh", "-c", "true")) { }
        assertEquals(127, r.exitCode)
        assertEquals(0, calls())
        assertEquals(ComputerStatus.Disabled, runtime().ensureRootfs())
        assertNull(rootfs.listFiles())
    }

    @Test
    fun `agent bridge runs through proot in guest root`() = runTest {
        val rt = runtime()
        rt.ensureRootfs()
        val r = CosAgentBridge.exec(rt, "cd /tmp && echo \"a b\"")
        assertEquals(0, r.exitCode)
        val a = args(r.stdout)
        assertEquals("/root", a[a.indexOf("-w") + 1])
        assertEquals(listOf("/bin/sh", "-c", "cd /tmp && echo \"a b\""), a.takeLast(3))
    }

    @Test
    fun `output is capped`() {
        val b = LocalComputerRuntime.CappedBuffer(10)
        assertTrue(b.appendLine("12345"))
        b.appendLine("1234567890")
        b.appendLine("more")
        assertTrue(b.text().length <= 10 + 200)
    }

    internal companion object {
        val FAKE_PROOT = """
            #!/bin/sh
            d="${'$'}(dirname "${'$'}0")"
            n=${'$'}(cat "${'$'}d/calls" 2>/dev/null || echo 0)
            echo ${'$'}((n+1)) > "${'$'}d/calls"
            if [ -f "${'$'}d/eperm" ] && [ -z "${'$'}PROOT_NO_SECCOMP" ]; then
              echo 'proot error: execve("/bin/sh"): Operation not permitted' >&2
              echo 'proot info: It seems that seccomp is not supported; set PROOT_NO_SECCOMP=1' >&2
              exit 1
            fi
            for a in "${'$'}@"; do echo "ARG:${'$'}a"; done
            echo "ENV:PROOT_LOADER=${'$'}PROOT_LOADER"
            echo "ENV:PROOT_TMP_DIR=${'$'}PROOT_TMP_DIR"
            echo "ENV:LD_LIBRARY_PATH=${'$'}LD_LIBRARY_PATH"
            echo "ENV:PROOT_NO_SECCOMP=${'$'}PROOT_NO_SECCOMP"
            echo "ENV:LD_PRELOAD=${'$'}LD_PRELOAD"
            if [ -f "${'$'}d/hang" ]; then
              # Simulates proot's SIGQUIT handler (kill_all_tracees) unless told to ignore it.
              if [ -f "${'$'}d/ignore_quit" ]; then
                trap '' QUIT
              else
                trap 'echo QUIT >> "${'$'}d/events"; kill -9 ${'$'}c 2>/dev/null; exit 131' QUIT
              fi
              sleep 300 >/dev/null 2>&1 &
              c=${'$'}!
              echo ${'$'}c > "${'$'}d/child.pid"
              wait ${'$'}c
            fi
            exit 0
        """.trimIndent() + "\n"
    }
}
