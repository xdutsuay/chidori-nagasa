package com.druk.lmplayground.computer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device COS runtime: provisions Alpine minirootfs (download + tar) with
 * offline stub fallback; runs commands with cwd in the COS rootfs; supports
 * line-streamed output for the live terminal.
 */
class LocalComputerRuntime(
    private val context: Context,
    private val flags: ComputerFlagStore,
    private val batteryPercent: () -> Int = { 100 },
    private val thermalStatus: () -> Int = { 0 },
    private val bootstrap: CosRootfsBootstrap = CosRootfsBootstrap(),
) : ComputerRuntime {

    private val running = AtomicBoolean(false)
    private val activeProcess = AtomicReference<Process?>(null)
    private var lastError: String? = null

    private val cosDir: File get() = File(context.filesDir, CosConstants.COS_DIR)
    private val rootfsDir: File get() = File(cosDir, "rootfs")
    private val cacheDir: File get() = File(cosDir, "cache")
    private val readyMarker: File get() = File(rootfsDir, CosConstants.READY_MARKER)

    fun rootfsPresent(): Boolean = readyMarker.isFile

    fun usedBytes(): Long = cosDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Mode from `.cos_ready` plus used bytes under `cos/` (prefs untouched). */
    fun rootfsSnapshot(): CosRootfsSnapshot {
        val mode = if (readyMarker.isFile) {
            runCatching { CosRootfsInfo.parseMode(readyMarker.readText()) }.getOrNull()
        } else {
            null
        }
        return CosRootfsSnapshot(
            mode = mode,
            usedBytes = usedBytes(),
            present = rootfsPresent(),
        )
    }

    /** Deletes rootfs tree only; SharedPreferences / session id stay. */
    fun deleteRootfsKeepPrefs() {
        if (rootfsDir.exists()) {
            rootfsDir.deleteRecursively()
        }
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

    private fun ensureRootfsInternal(onProgress: (String) -> Unit): ComputerStatus {
        if (!flags.isComputerEnabled()) return ComputerStatus.Disabled
        val block = ComputerResourceGates.blockReason(batteryPercent(), thermalStatus())
        if (block != null) {
            lastError = block
            return ComputerStatus.Error(block)
        }
        if (rootfsPresent()) {
            lastError = null
            onProgress("Rootfs already present")
            return ComputerStatus.Ready
        }
        val incoming = CosConstants.ALPINE_DOWNLOAD_BUDGET_BYTES
        if (ComputerStorageBudget.wouldExceed(usedBytes(), incoming)) {
            val msg = "COS storage soft cap would be exceeded"
            lastError = msg
            return ComputerStatus.Error(msg)
        }
        onProgress("Trying Alpine download (${bootstrap.alpineArch()})…")
        val (ok, log) = bootstrap.downloadAndExtract(rootfsDir, cacheDir) { p ->
            onProgress(
                p.message + if (p.bytesRead > 0) {
                    " (${CosRootfsInfo.formatBytes(p.bytesRead)})"
                } else {
                    ""
                },
            )
        }
        if (ok && rootfsPresent()) {
            lastError = null
            onProgress(log)
            return ComputerStatus.Ready
        }
        onProgress("Download/extract failed: $log — using offline stub")
        val stubMsg = bootstrap.offlineStub(rootfsDir, cosDir)
        lastError = null
        onProgress(stubMsg)
        return if (rootfsPresent()) ComputerStatus.Ready else ComputerStatus.Error("stub failed")
    }

    override suspend fun exec(argv: List<String>): ComputerExecResult =
        execStreaming(argv) { }

    /** Absolute directory for a rootfs-relative cwd, or null if outside / invalid. */
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
     * Runs [argv]; invokes [onLine] for each stdout/stderr line as it arrives.
     * [cwdRelative] is relative to the COS rootfs (default `.`).
     * Bounded by [CosConstants.EXEC_TIMEOUT_MS]; timed-out processes are destroyed
     * (exit 124). [stop] also destroys the active process mid-wait.
     */
    suspend fun execStreaming(
        argv: List<String>,
        cwdRelative: String = CosCwd.DEFAULT,
        onLine: (String) -> Unit,
    ): ComputerExecResult = withContext(Dispatchers.IO) {
        if (!flags.isComputerEnabled()) {
            return@withContext ComputerExecResult(127, "", "computer disabled")
        }
        if (!rootfsPresent()) {
            return@withContext ComputerExecResult(127, "", "COS rootfs missing — run ensureRootfs")
        }
        if (argv.isEmpty()) {
            return@withContext ComputerExecResult(2, "", "empty argv")
        }
        val workDir = workDirFor(cwdRelative)
            ?: return@withContext ComputerExecResult(1, "", "cwd outside COS rootfs")
        if (!workDir.isDirectory) {
            return@withContext ComputerExecResult(1, "", "cwd not a directory: $cwdRelative")
        }
        val block = ComputerResourceGates.blockReason(batteryPercent(), thermalStatus())
        if (block != null) {
            return@withContext ComputerExecResult(1, "", block)
        }
        running.set(true)
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        try {
            val pb = ProcessBuilder(argv)
                .directory(workDir)
                .redirectErrorStream(false)
            val proc = pb.start()
            activeProcess.set(proc)
            val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))
            val stdoutThread = Thread {
                stdoutReader.lineSequence().forEach { line ->
                    synchronized(outBuf) { outBuf.appendLine(line) }
                    onLine(line)
                }
            }.also { it.start() }
            val stderrThread = Thread {
                stderrReader.lineSequence().forEach { line ->
                    synchronized(errBuf) { errBuf.appendLine(line) }
                    onLine("[err] $line")
                }
            }.also { it.start() }
            val finished = proc.waitFor(CosConstants.EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                stdoutThread.join(5_000)
                stderrThread.join(5_000)
                val msg = "exec timed out after ${CosConstants.EXEC_TIMEOUT_MS}ms"
                onLine("[err] $msg")
                return@withContext ComputerExecResult(
                    124,
                    synchronized(outBuf) { outBuf.toString() },
                    synchronized(errBuf) { errBuf.appendLine(msg).toString() },
                )
            }
            stdoutThread.join(5_000)
            stderrThread.join(5_000)
            ComputerExecResult(
                proc.exitValue(),
                synchronized(outBuf) { outBuf.toString() },
                synchronized(errBuf) { errBuf.toString() },
            )
        } catch (t: Throwable) {
            activeProcess.getAndSet(null)?.destroyForcibly()
            ComputerExecResult(1, "", t.message ?: "exec failed")
        } finally {
            activeProcess.set(null)
            running.set(false)
        }
    }

    override fun stop() {
        activeProcess.getAndSet(null)?.destroyForcibly()
        running.set(false)
    }

    fun pruneIfNeeded(): List<String> {
        val entries = cosDir.walkTopDown()
            .filter { it.isFile }
            .map {
                ComputerPruner.Entry(
                    relativePath = it.relativeTo(cosDir).path.replace('\\', '/'),
                    bytes = it.length(),
                    lastUsedEpochMs = it.lastModified(),
                )
            }
            .toList()
        val plan = ComputerPruner.planPrune(entries, usedBytes())
        for (rel in plan) {
            File(cosDir, rel).delete()
        }
        return plan
    }

    companion object {
        fun create(context: Context): LocalComputerRuntime {
            val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
            return LocalComputerRuntime(
                context = context.applicationContext,
                flags = ComputerFlagStore.fromSharedPreferences(prefs),
            )
        }
    }
}
