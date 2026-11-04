package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProotCommandTest {
    private val lib = "/data/app/~~x/com.xdutsuay.chidori.nagasa.dev-1/lib/arm64"
    private val rootfs = "/data/user/0/com.xdutsuay.chidori.nagasa.dev/files/cos/rootfs"

    @Test
    fun `argv matches the section 3_5 proot command exactly`() {
        val argv = ProotCommand.buildArgv(lib, rootfs, "/root", CosShellLexer.toArgv("uname -a"))
        val expected = listOf(
            "$lib/libproot.so", "--kill-on-exit", "--link2symlink", "-0", "-L",
            "-r", rootfs,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "$rootfs/tmp:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "/bin/sh", "-c", "uname -a",
        )
        assertEquals(expected, argv)
    }

    @Test
    fun `argv is identical for seccomp and no-seccomp paths - only env differs`() {
        // One argv builder for both paths; the fallback is purely PROOT_NO_SECCOMP=1 in env.
        val a = ProotCommand.buildArgv(lib, rootfs, "/", listOf("/bin/sh", "-c", "true"))
        val b = ProotCommand.buildArgv(lib, rootfs, "/", listOf("/bin/sh", "-c", "true"))
        assertEquals(a, b)
        assertFalse(a.any { it.contains("SECCOMP") })
    }

    @Test
    fun `command with spaces quotes and semicolon stays a single guest argument`() {
        val line = "echo \"a b\" 'c;d'; rm -rf /tmp/x && echo \$(id -u) | tee /root/o"
        val argv = ProotCommand.buildArgv(lib, rootfs, "/root", CosShellLexer.toArgv(line))
        assertEquals(line, argv.last())
        assertEquals(listOf("/bin/sh", "-c"), argv.subList(argv.size - 3, argv.size - 1))
        // Nothing from the command leaks into proot's own flags.
        val prootPart = argv.subList(0, argv.indexOf("/usr/bin/env"))
        assertTrue(prootPart.none { it.contains("echo") || it.contains(";") })
    }

    @Test
    fun `env seccomp path sets loader tmp ld path and strips LD_PRELOAD`() {
        val env = ProotCommand.buildEnv(
            nativeLibDir = lib,
            tmpDir = "/data/user/0/x/cache/proot-tmp",
            l2sDir = "/data/user/0/x/files/cos/l2s",
            noSeccomp = false,
            hostEnv = mapOf(
                "LD_PRELOAD" to "/system/lib64/libsigchain.so",
                "LD_LIBRARY_PATH" to "/vendor/lib64",
                "PROOT_NO_SECCOMP" to "1",
                "ANDROID_DATA" to "/data",
            ),
        )
        assertEquals("$lib/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("/data/user/0/x/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertEquals("/data/user/0/x/files/cos/l2s", env["PROOT_L2S_DIR"])
        assertEquals(lib, env["LD_LIBRARY_PATH"])
        assertNull(env["LD_PRELOAD"])
        assertNull("stale host PROOT_NO_SECCOMP must not leak", env["PROOT_NO_SECCOMP"])
        assertEquals("/data", env["ANDROID_DATA"])
    }

    @Test
    fun `env no-seccomp path adds PROOT_NO_SECCOMP=1 and nothing else changes`() {
        val on = ProotCommand.buildEnv(lib, "/t", null, noSeccomp = false)
        val off = ProotCommand.buildEnv(lib, "/t", null, noSeccomp = true)
        assertEquals("1", off["PROOT_NO_SECCOMP"])
        assertEquals(on, off - "PROOT_NO_SECCOMP")
        assertNull(on["PROOT_L2S_DIR"])
    }

    @Test
    fun `retry without seccomp only on proot's own EPERM or hint`() {
        assertTrue(
            ProotCommand.shouldRetryWithoutSeccomp(
                1, "proot error: execve(\"/bin/sh\"): Operation not permitted\n", false,
            ),
        )
        assertTrue(
            ProotCommand.shouldRetryWithoutSeccomp(
                255, "proot info: It seems seccomp is broken; try PROOT_NO_SECCOMP=1\n", false,
            ),
        )
        // Already on the fallback path, success, timeout, or guest-originated text: no retry.
        assertFalse(ProotCommand.shouldRetryWithoutSeccomp(1, "proot error: execve: Operation not permitted", true))
        assertFalse(ProotCommand.shouldRetryWithoutSeccomp(0, "proot error: execve: Operation not permitted", false))
        assertFalse(ProotCommand.shouldRetryWithoutSeccomp(124, "proot error: execve: EPERM", false))
        assertFalse(ProotCommand.shouldRetryWithoutSeccomp(1, "rm: can't remove 'x': Operation not permitted", false))
        assertFalse(ProotCommand.shouldRetryWithoutSeccomp(127, "sh: foo: not found", false))
    }

    @Test
    fun `fallback is persisted only when proot started the guest`() {
        assertTrue(ProotCommand.fallbackWorked(0, ""))
        assertTrue(ProotCommand.fallbackWorked(1, "ls: /nope: No such file or directory"))
        assertFalse(ProotCommand.fallbackWorked(1, "proot error: execve(\"/bin/sh\"): Operation not permitted"))
        assertFalse(ProotCommand.fallbackWorked(124, ""))
    }

    @Test
    fun `guest cwd maps rootfs-relative paths`() {
        assertEquals("/", ProotCommand.guestCwd("."))
        assertEquals("/", ProotCommand.guestCwd(""))
        assertEquals("/root", ProotCommand.guestCwd("root"))
        assertEquals("/usr/lib", ProotCommand.guestCwd("usr/./lib"))
        assertEquals("/", ProotCommand.guestCwd("../.."))
    }

    @Test
    fun `arch comes from installed ABI dir and only 64-bit ABIs are supported`() {
        assertEquals("arm64-v8a", ProotCommand.abiForNativeLibDir("/data/app/x/lib/arm64"))
        assertEquals("x86_64", ProotCommand.abiForNativeLibDir("/data/app/x/lib/x86_64"))
        assertEquals("aarch64", ProotCommand.alpineArchForAbi("arm64-v8a"))
        assertEquals("x86_64", ProotCommand.alpineArchForAbi("x86_64"))
        assertNull(ProotCommand.alpineArchForAbi("armeabi-v7a"))
        assertNull(ProotCommand.alpineArchForAbi("x86"))
    }

    @Test
    fun `missingLibs reports absent proot pieces`() {
        val dir = Files.createTempDirectory("nl").toFile()
        assertEquals(ProotCommand.REQUIRED_LIBS, ProotCommand.missingLibs(dir.path))
        ProotCommand.REQUIRED_LIBS.forEach { java.io.File(dir, it).writeText("x") }
        assertTrue(ProotCommand.missingLibs(dir.path).isEmpty())
        dir.deleteRecursively()
    }
}
