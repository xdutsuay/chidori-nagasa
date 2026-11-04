package com.druk.lmplayground.computer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device COS runtime: provisions the pinned, sha256-verified Alpine minirootfs
 * and runs every command INSIDE it through proot (`libproot.so` shipped in jniLibs).
 *
 * - Provisioning: [CosRootfsBootstrap] + [CosProvisioning]. An install from another
 *   Alpine version (or the legacy offline stub) is wiped and re-provisioned.
 *   Failures surface as [ComputerStatus.Error]; there is no host-shell fallback.
 * - Exec: guest argv (normally `/bin/sh -c <line>` from [CosShellLexer.toArgv]) is
 *   wrapped by [ProotCommand.buildArgv] / [ProotCommand.buildEnv]. If proot fails with
 *   the seccomp/EPERM signature it is retried once with `PROOT_NO_SECCOMP=1`, which is
 *   then persisted ([ComputerFlagStore.isProotNoSeccomp]) and logged under tag `COS`.
 * - Limits: [CosConstants.EXEC_TIMEOUT_MS] (exit 124) and [CosConstants.EXEC_OUTPUT_CAP_CHARS]
 *   per stream.
 * - Timeout / [stop]: proot is never SIGKILLed first. `--kill-on-exit` only kills the
 *   remaining tracees when the *initial* tracee exits; it does nothing if proot itself is
 *   SIGKILLed (no PTRACE_O_EXITKILL), which would orphan guest processes outside proot's
 *   path translation. [ProotTerminator] sends SIGQUIT (proot's `kill_all_tracees`
 *   handler), waits ~2 s, then `destroyForcibly()`, then SIGKILLs any surviving
 *   descendant found in `/proc` before the signal.
 *
 * Used by the Computer screen terminal, [CosAgentBridge] (`cos_exec`) and
 * [CosComputerService] alike.
 */
class LocalComputerRuntime(
    private val context: Context,
    private val flags: ComputerFlagStore,
    private val batteryPercent: () -> Int = { 100 },
    private val thermalStatus: () -> Int = { 0 },
    private val bootstrap: CosRootfsBootstrap = CosRootfsBootstrap(),
    private val nativeLibDirOverride: String? = null,
    private val log: (String) -> Unit = { msg -> runCatching { Log.i(LOG_TAG, msg) } },
    private val execTimeoutMs: Long = CosConstants.EXEC_TIMEOUT_MS,
    killGraceMs: Long = ProotTerminator.DEFAULT_GRACE_MS,
    sendSignal: (pid: Int, signal: Int) -> Unit = { pid, sig -> android.os.Process.sendSignal(pid, sig) },
    launchPidOf: (Process) -> Int = ProotTerminator::pidOf,
) : ComputerRuntime {

    private val terminator = ProotTerminator(sendSignal, killGraceMs, log, resolvePid = launchPidOf)

    private val running = AtomicBoolean(false)
    private val activeProcess = AtomicReference<ProotTerminator.Handle?>(null)
    private var lastError: String? = null

    private val cosDir: File get() = File(context.filesDir, CosConstants.COS_DIR)
    private val rootfsDir: File get() = File(cosDir, "rootfs")
    private val cacheDir: File get() = File(cosDir, "cache")
    private val l2sDir: File get() = File(cosDir, "l2s")
    private val prootTmpDir: File get() = File(context.cacheDir, "proot-tmp")
    private val readyMarker: File get() = File(rootfsDir, CosConstants.READY_MARKER)

    /** `ApplicationInfo.nativeLibraryDir` – the only exec-able location for app binaries. */
    val nativeLibDir: String
        get() = nativeLibDirOverride ?: context.applicationInfo.nativeLibraryDir.orEmpty()

    /** Alpine arch for the ABI this APK was installed with, or null if unsupported. */
    fun alpineArch(): String? =
        ProotCommand.alpineArchForAbi(ProotCommand.abiForNativeLibDir(nativeLibDir))

    private fun markerTextOrNull(): String? =
        if (readyMarker.isFile) runCatching { readyMarker.readText() }.getOrNull() else null

    /** True when a verified Alpine install of the pinned version is present. */
    fun rootfsPresent(): Boolean {
        val marker = markerTextOrNull() ?: return false
        return !CosProvisioning.needsReprovision(marker, CosConstants.ALPINE_VERSION, alpineArch())
    }

    fun usedBytes(): Long = CosFiles.usedBytes(cosDir)

    /** Mode from `.cos_ready` plus used bytes under `cos/` (prefs untouched). */
    fun rootfsSnapshot(): CosRootfsSnapshot {
        val mode = markerTextOrNull()?.let { CosRootfsInfo.parseMode(it) }
        return CosRootfsSnapshot(
            mode = mode,
            usedBytes = usedBytes(),
            present = rootfsPresent(),
        )
    }

    /** Deletes rootfs tree only (symlink-safe); SharedPreferences / session id stay. */
    fun deleteRootfsKeepPrefs() {
        CosFiles.deleteTree(rootfsDir)
        CosFiles.deleteTree(CosProvisioning.partialDirFor(rootfsDir))
    }

    suspend fun reinstallRootfsStreaming(onProgress: (String) -> Unit): ComputerStatus =
        withContext(Dispatchers.IO) {
            onProgress("Deleting rootfs (keeping session prefs)…")
            deleteRootfsKeepPrefs()
            ensureRootfsInternal(onProgress)
        }

    override fun status(): ComputerStatus {
        lastError?.let { return ComputerStatus.Error(it) }
        return ComputerGate.resolveStatus(
            enabled = flags.isComputerEnabled(),
            rootfsPresent = rootfsPresent(),
            running = running.get(),
        )
    }

    override suspend fun ensureRootfs(): ComputerStatus = withContext(Dispatchers.IO) {
        ensureRootfsInternal { }
    }

    suspend fun ensureRootfsStreaming(onProgress: (String) -> Unit): ComputerStatus =
        withContext(Dispatchers.IO) {
            ensureRootfsInternal(onProgress)
        }

    private fun fail(msg: String, onProgress: (String) -> Unit): ComputerStatus {
        lastError = msg
        onProgress(msg)
        log("provision failed: $msg")
        return ComputerStatus.Error(msg)
    }

    private fun ensureRootfsInternal(onProgress: (String) -> Unit): ComputerStatus {
        if (!flags.isComputerEnabled()) return ComputerStatus.Disabled
        val block = ComputerResourceGates.blockReason(batteryPercent(), thermalStatus())
        if (block != null) return fail(block, onProgress)
        val arch = alpineArch()
            ?: return fail("Unsupported ABI for COS (${File(nativeLibDir).name}); needs arm64-v8a or x86_64", onProgress)
        val marker = markerTextOrNull()
        if (marker != null && CosProvisioning.needsReprovision(marker, CosConstants.ALPINE_VERSION, arch)) {
            val old = CosProvisioning.markerValue(marker, "version")
                ?: CosRootfsInfo.parseMode(marker) ?: "unknown"
            onProgress("Existing rootfs ($old) != Alpine ${CosConstants.ALPINE_VERSION}; deleting and re-provisioning…")
            log("reprovision: $old -> ${CosConstants.ALPINE_VERSION}")
            CosProvisioning.wipe(rootfsDir, cacheDir)
        }
        if (rootfsPresent()) {
            lastError = null
            onProgress("Rootfs already present (Alpine ${CosConstants.ALPINE_VERSION})")
            return ComputerStatus.Ready
        }
        CosFiles.deleteTree(CosProvisioning.partialDirFor(rootfsDir))
        if (ComputerStorageBudget.wouldExceed(usedBytes(), CosConstants.ALPINE_DOWNLOAD_BUDGET_BYTES)) {
            return fail("COS storage soft cap would be exceeded", onProgress)
        }
        onProgress("Downloading Alpine ${CosConstants.ALPINE_VERSION} ($arch)…")
        val (ok, msg) = bootstrap.downloadAndExtract(rootfsDir, cacheDir, arch) { p ->
            onProgress(
                p.message + if (p.bytesRead > 0) " (${CosRootfsInfo.formatBytes(p.bytesRead)})" else "",
            )
        }
        if (ok && rootfsPresent()) {
            lastError = null
            onProgress(msg)
            return ComputerStatus.Ready
        }
        return fail(msg, onProgress)
    }

    override suspend fun exec(argv: List<String>): ComputerExecResult =
        execStreaming(argv) { }

    /** Absolute host directory for a rootfs-relative cwd, or null if outside / invalid. */
    fun workDirFor(cwdRelative: String): File? {
        val norm = CosCwd.normalizeOrNull(cwdRelative.ifBlank { CosCwd.DEFAULT }) ?: return null
        val dir = if (norm == CosCwd.DEFAULT) rootfsDir else File(rootfsDir, norm)
        return try {
            val rootCanon = rootfsDir.canonicalFile
            val dirCanon = dir.canonicalFile
            val under = dirCanon == rootCanon ||
                dirCanon.path.startsWith(rootCanon.path + File.separator)
            if (under) dirCanon else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs guest [argv] inside Alpine via proot; invokes [onLine] for each
     * stdout/stderr line as it arrives. [cwdRelative] is relative to the rootfs
     * (default `.` = guest `/`; the agent uses `root` = `/root`).
     */
    suspend fun execStreaming(
        argv: List<String>,
        cwdRelative: String = CosCwd.DEFAULT,
        onLine: (String) -> Unit,
    ): ComputerExecResult = withContext(Dispatchers.IO) {
        if (!flags.isComputerEnabled()) {
            return@withContext early(127, "computer disabled", onLine)
        }
        if (!rootfsPresent()) {
            return@withContext early(127, "COS rootfs missing — set up Alpine on the Computer screen", onLine)
        }
        if (argv.isEmpty()) {
            return@withContext early(2, "empty argv", onLine)
        }
        val workDir = workDirFor(cwdRelative)
            ?: return@withContext early(1, "cwd outside COS rootfs", onLine)
        if (!workDir.isDirectory) {
            return@withContext early(1, "cwd not a directory: $cwdRelative", onLine)
        }
        val block = ComputerResourceGates.blockReason(batteryPercent(), thermalStatus())
        if (block != null) {
            return@withContext early(1, block, onLine)
        }
        val missing = ProotCommand.missingLibs(nativeLibDir)
        if (missing.isNotEmpty()) {
            // Never fall back to running on the Android host.
            return@withContext early(127, "proot runtime missing from APK ($nativeLibDir: ${missing.joinToString()})", onLine)
        }
        prepareGuest()
        val hostArgv = ProotCommand.buildArgv(
            nativeLibDir = nativeLibDir,
            rootfsPath = rootfsDir.absolutePath,
            guestCwd = ProotCommand.guestCwd(cwdRelative),
            guestArgv = argv,
        )
        val noSeccomp = flags.isProotNoSeccomp()
        val first = runProot(hostArgv, noSeccomp, onLine)
        if (!ProotCommand.shouldRetryWithoutSeccomp(first.exitCode, first.stderr, noSeccomp)) {
            log("exec path=proot seccomp=${if (noSeccomp) "off" else "on"} exit=${first.exitCode}")
            return@withContext first
        }
        log("proot failed with seccomp (exit ${first.exitCode}); retrying with PROOT_NO_SECCOMP=1")
        onLine("[cos] retrying with PROOT_NO_SECCOMP=1")
        val second = runProot(hostArgv, true, onLine)
        if (ProotCommand.fallbackWorked(second.exitCode, second.stderr)) {
            flags.setProotNoSeccomp(true)
        }
        log("exec path=proot seccomp=off(fallback) exit=${second.exitCode}")
        second
    }

    /** Pre-exec refusal: surfaced on the live terminal too (it never reaches proot). */
    private fun early(code: Int, msg: String, onLine: (String) -> Unit): ComputerExecResult {
        onLine("[err] $msg")
        return ComputerExecResult(code, "", msg)
    }

    /** Guest dirs proot binds / apk writes; resolv.conf re-written if missing. */
    private fun prepareGuest() {
        prootTmpDir.mkdirs()
        l2sDir.mkdirs()
        if (!File(rootfsDir, "etc/resolv.conf").isFile || !File(rootfsDir, "tmp").isDirectory ||
            !File(rootfsDir, "root").isDirectory
        ) {
            CosProvisioning.writeGuestFiles(rootfsDir)
        }
    }

    private fun runProot(
        hostArgv: List<String>,
        noSeccomp: Boolean,
        onLine: (String) -> Unit,
    ): ComputerExecResult {
        running.set(true)
        val outBuf = CappedBuffer(CosConstants.EXEC_OUTPUT_CAP_CHARS)
        val errBuf = CappedBuffer(CosConstants.EXEC_OUTPUT_CAP_CHARS)
        try {
            val pb = ProcessBuilder(hostArgv)
                .directory(rootfsDir)
                .redirectErrorStream(false)
            val env = pb.environment()
            val built = ProotCommand.buildEnv(
                nativeLibDir = nativeLibDir,
                tmpDir = prootTmpDir.absolutePath,
                l2sDir = l2sDir.absolutePath,
                noSeccomp = noSeccomp,
                hostEnv = HashMap(env),
            )
            env.clear()
            env.putAll(built)
            // pid captured right at launch and kept with the running command (Stop/timeout).
            val handle = terminator.launch { pb.start() }
            val proc = handle.proc
            proc.outputStream.close() // no stdin: commands must be non-interactive
            activeProcess.set(handle)
            val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
            val stdoutThread = Thread {
                runCatching {
                    stdoutReader.lineSequence().forEach { line ->
                        if (outBuf.appendLine(line)) onLine(line)
                    }
                }
            }.also { it.start() }
            val stderrThread = Thread {
                runCatching {
                    stderrReader.lineSequence().forEach { line ->
                        if (errBuf.appendLine(line)) onLine("[err] $line")
                    }
                }
            }.also { it.start() }
            val finished = proc.waitFor(execTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                activeProcess.compareAndSet(handle, null)
                terminator.terminate(handle, "timeout")
                stdoutThread.join(5_000)
                stderrThread.join(5_000)
                val msg = "exec timed out after ${execTimeoutMs}ms"
                onLine("[err] $msg")
                errBuf.appendLine(msg)
                return ComputerExecResult(124, outBuf.text(), errBuf.text())
            }
            stdoutThread.join(5_000)
            stderrThread.join(5_000)
            return ComputerExecResult(proc.exitValue(), outBuf.text(), errBuf.text())
        } catch (t: Throwable) {
            activeProcess.getAndSet(null)?.let { terminator.terminate(it, "error") }
            return ComputerExecResult(1, "", "proot exec failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            activeProcess.set(null)
            running.set(false)
        }
    }

    /**
     * Stops the running command via [ProotTerminator] (SIGQUIT → ~2 s grace →
     * destroyForcibly → sweep). Runs off the caller's thread (the UI calls this).
     * `running` is cleared by [runProot] once the process is really gone.
     */
    override fun stop() {
        val handle = activeProcess.getAndSet(null) ?: return
        Thread({ terminator.terminate(handle, "stop") }, "cos-stop").apply { isDaemon = true }.start()
    }

    fun pruneIfNeeded(): List<String> {
        val entries = CosFiles.regularFiles(cosDir).map { (file, bytes) ->
            ComputerPruner.Entry(
                relativePath = file.relativeTo(cosDir).path.replace('\\', '/'),
                bytes = bytes,
                lastUsedEpochMs = file.lastModified(),
            )
        }
        val plan = ComputerPruner.planPrune(entries, entries.sumOf { it.bytes })
        for (rel in plan) {
            File(cosDir, rel).delete()
        }
        return plan
    }

    /** Thread-safe line buffer that stops growing at [cap] chars and notes the truncation once. */
    internal class CappedBuffer(private val cap: Int) {
        private val sb = StringBuilder()
        private var truncated = false

        /** @return false once the cap was hit (line dropped). */
        @Synchronized
        fun appendLine(line: String): Boolean {
            if (truncated) return false
            if (sb.length + line.length + 1 > cap) {
                truncated = true
                sb.append("…[output truncated at ").append(cap).append(" chars]\n")
                return false
            }
            sb.append(line).append('\n')
            return true
        }

        @Synchronized
        fun text(): String = sb.toString()
    }

    companion object {
        /** Logcat tag for COS runtime decisions (exec path, seccomp fallback, provisioning). */
        const val LOG_TAG = "COS"

        fun create(context: Context): LocalComputerRuntime {
            val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
            return LocalComputerRuntime(
                context = context.applicationContext,
                flags = ComputerFlagStore.fromSharedPreferences(prefs),
            )
        }
    }
}
