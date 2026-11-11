package com.xdutsuay.chidori.computer

import java.io.File

/**
 * Pure (Android-free) builder for the proot command line that runs every COS
 * command inside the Alpine rootfs.
 *
 * This is the ONLY place that turns a guest command into a host process argv;
 * [LocalComputerRuntime.execStreaming] (used by the Computer screen terminal,
 * [CosAgentBridge] / `cos_exec`, and [CosComputerService]) always goes through it,
 * so there is no exec path that skips proot and runs on the Android host.
 *
 * Layout on device (see docs/COS.md):
 * - `nativeLibDir/libproot.so`         – Termux proot 5.1.107.102 (patched, jniLibs)
 * - `nativeLibDir/libproot-loader.so`  – unbundled proot loader ([ENV_LOADER])
 * - `nativeLibDir/libtalloc.so`, `libandroid-shmem.so` – proot's shared deps
 *
 * Binaries must be exec'd from `ApplicationInfo.nativeLibraryDir`: apps targeting
 * Android 10+ cannot execve() files in their data dir (W^X).
 */
object ProotCommand {
    const val PROOT_LIB = "libproot.so"
    const val LOADER_LIB = "libproot-loader.so"

    /** Shared libraries proot needs next to it (resolved via `$ORIGIN` RUNPATH / LD_LIBRARY_PATH). */
    val REQUIRED_LIBS: List<String> = listOf(PROOT_LIB, LOADER_LIB, "libtalloc.so", "libandroid-shmem.so")

    const val ENV_LOADER = "PROOT_LOADER"
    const val ENV_TMP_DIR = "PROOT_TMP_DIR"
    const val ENV_NO_SECCOMP = "PROOT_NO_SECCOMP"
    const val ENV_L2S_DIR = "PROOT_L2S_DIR"

    /** Alpine-standard PATH inside the guest. */
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Host env vars that must never reach proot (Android preload / linker hooks). */
    val STRIPPED_HOST_ENV: Set<String> = setOf("LD_PRELOAD", "LD_LIBRARY_PATH")

    /** Clean guest environment (applied with `env -i`, so no Android env leaks in). */
    val GUEST_ENV: List<String> = listOf(
        "HOME=${CosConstants.GUEST_HOME}",
        "USER=root",
        "PATH=$GUEST_PATH",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
    )

    /**
     * Guest argv for a user/model command line. The line is passed as ONE argument
     * to Alpine's `/bin/sh -c`, never split or concatenated into proot flags.
     */
    fun guestShellArgv(commandLine: String): List<String> =
        listOf(CosConstants.GUEST_SH, "-c", commandLine)

    /**
     * Maps a rootfs-relative cwd (see [CosCwd], `.` = rootfs root) to an absolute
     * guest path for proot's `-w`. Returns `/` for blank / `.`.
     */
    fun guestCwd(cwdRelative: String): String {
        val norm = CosCwd.normalizeOrNull(cwdRelative.ifBlank { CosCwd.DEFAULT }) ?: return "/"
        return if (norm == CosCwd.DEFAULT) "/" else "/" + norm.trimStart('/')
    }

    /**
     * Full host argv: `libproot.so <flags> -r <rootfs> <binds> -w <cwd> /usr/bin/env -i <GUEST_ENV> <guestArgv>`.
     *
     * Flags (mirrors proot-distro / UserLAnd):
     * - `--kill-on-exit`: when the initial guest process exits, kill the rest of the guest
     *   tree (stray background jobs). It does NOT fire if proot itself is killed; timeout /
     *   Stop go through [ProotTerminator] (SIGQUIT, then force kill).
     * - `--link2symlink`: emulate hard links (Android FS denies link(); apk needs it)
     * - `-0`: fake uid 0 so `apk` can chown
     * - `-L`: Termux extension, fix lstat on symlinks
     * - binds `/dev`, `/proc`, `/sys`, `/dev/urandom:/dev/random`, `/proc/self/fd:/dev/fd`,
     *   and `<rootfs>/tmp:/dev/shm` (writable tmp lives in the rootfs at `/tmp`).
     *
     * @param nativeLibDir `ApplicationInfo.nativeLibraryDir`
     * @param rootfsPath absolute host path of the Alpine rootfs
     * @param guestCwd absolute guest working dir (see [guestCwd])
     * @param guestArgv argv to run inside the guest, normally [guestShellArgv]
     */
    fun buildArgv(
        nativeLibDir: String,
        rootfsPath: String,
        guestCwd: String,
        guestArgv: List<String>,
    ): List<String> {
        require(guestArgv.isNotEmpty()) { "empty guest argv" }
        return buildList {
            // Always '/' — these are Android nativeLibraryDir paths; File.path on a
            // Windows CI/dev host would inject '\' and break argv equality + device parity.
            add(androidJoin(nativeLibDir, PROOT_LIB))
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-L")
            add("-r"); add(rootfsPath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("/dev/urandom:/dev/random")
            add("-b"); add("/proc/self/fd:/dev/fd")
            add("-b"); add("$rootfsPath/tmp:/dev/shm")
            add("-w"); add(guestCwd)
            add("/usr/bin/env"); add("-i")
            addAll(GUEST_ENV)
            addAll(guestArgv)
        }
    }

    /**
     * Environment for the proot HOST process. Starts from [hostEnv], removes
     * [STRIPPED_HOST_ENV], then sets:
     * - `PROOT_LOADER` = `<nativeLibDir>/libproot-loader.so` (mandatory for the Termux build)
     * - `PROOT_TMP_DIR` = [tmpDir] (an app cache dir; `/tmp` doesn't exist on Android)
     * - `PROOT_L2S_DIR` = [l2sDir] (link2symlink backing store) when non-null
     * - `LD_LIBRARY_PATH` = [nativeLibDir] for proot itself, in case bionic ignores the
     *   patched `$ORIGIN` RUNPATH. The guest never sees it (`env -i`).
     * - `PROOT_NO_SECCOMP=1` only when [noSeccomp] (fallback path)
     */
    fun buildEnv(
        nativeLibDir: String,
        tmpDir: String,
        l2sDir: String?,
        noSeccomp: Boolean,
        hostEnv: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val env = hostEnv.filterKeys { it !in STRIPPED_HOST_ENV && it != ENV_NO_SECCOMP }.toMutableMap()
        env[ENV_LOADER] = androidJoin(nativeLibDir, LOADER_LIB)
        env[ENV_TMP_DIR] = tmpDir
        if (l2sDir != null) env[ENV_L2S_DIR] = l2sDir
        env["LD_LIBRARY_PATH"] = androidPath(nativeLibDir)
        if (noSeccomp) env[ENV_NO_SECCOMP] = "1"
        return env
    }

    /** Normalize host-provided native lib dirs to Android-style forward slashes. */
    internal fun androidPath(path: String): String = path.replace('\\', '/')

    internal fun androidJoin(dir: String, name: String): String =
        androidPath(dir).trimEnd('/') + "/" + name

    /**
     * True when a proot run failed in the way the seccomp-accelerated tracer fails
     * on some kernels (execve -> EPERM; proot itself prints a `PROOT_NO_SECCOMP` hint).
     * Callers retry once with `PROOT_NO_SECCOMP=1` and persist the choice.
     */
    fun shouldRetryWithoutSeccomp(exitCode: Int, stderr: String, alreadyNoSeccomp: Boolean): Boolean {
        if (alreadyNoSeccomp || exitCode == 0 || exitCode == 124) return false
        // Only proot's own diagnostics count (lines starting "proot "), so a guest
        // command that merely prints "Operation not permitted" is never re-run.
        return stderr.lineSequence()
            .map { it.trim().lowercase() }
            .filter { it.startsWith("proot ") || it.startsWith("proot:") }
            .any { line ->
                line.contains(ENV_NO_SECCOMP.lowercase()) ||
                    (line.contains("execve") && (line.contains("operation not permitted") || line.contains("eperm")))
            }
    }

    /**
     * True when the `PROOT_NO_SECCOMP=1` retry got proot to start the guest (the guest
     * command itself may still exit non-zero). Only then is the fallback persisted.
     */
    fun fallbackWorked(exitCode: Int, stderr: String): Boolean {
        if (exitCode == 0) return true
        if (exitCode == 124) return false
        return !stderr.lowercase().contains("proot error")
    }

    /** Alpine arch for the ABI dir the APK was installed with (`lib/<abi>`), or null if unsupported. */
    fun alpineArchForAbi(abi: String): String? = when (abi) {
        "arm64-v8a", "arm64", "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> null
    }

    /**
     * Android ABI name for an installed `nativeLibraryDir`
     * (`/data/app/…/lib/arm64` → `arm64-v8a`, `…/lib/x86_64` → `x86_64`).
     */
    fun abiForNativeLibDir(nativeLibDir: String): String = when (val leaf = File(nativeLibDir).name) {
        "arm64" -> "arm64-v8a"
        else -> leaf
    }

    /** Names from [REQUIRED_LIBS] missing in [nativeLibDir] (empty when proot can run). */
    fun missingLibs(nativeLibDir: String): List<String> =
        REQUIRED_LIBS.filterNot { File(nativeLibDir, it).isFile }
}
