package com.druk.lmplayground.computer

import java.io.File

/** Pure parsers for `/proc/<pid>/{status,stat,statm,cmdline}` used to measure COS processes. */
object CosProcStats {

    fun parseUid(statusText: String): Int? {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("Uid:") } ?: return null
        return line.removePrefix("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
    }

    /** utime + stime (fields 14 and 15); the comm field may contain spaces and ')'. */
    fun parseCpuTicks(statText: String): Long? {
        val close = statText.lastIndexOf(')')
        if (close < 0) return null
        val fields = statText.substring(close + 1).trim().split(Regex("\\s+"))
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return utime + stime
    }

    fun parseRssPages(statmText: String): Long? =
        statmText.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()

    /**
     * True for processes started inside COS (proot and its guests). Excludes the app
     * process itself and every `<package>:<name>` service process such as `:llama`.
     */
    fun isCosProcess(pid: Int, cmdline: String, appPid: Int, packageName: String): Boolean {
        if (pid == appPid) return false
        val first = cmdline.substringBefore('\u0000')
        if (first.isBlank()) return false
        return !first.startsWith(packageName)
    }

    fun cpuPercent(
        prevTicks: Long,
        nowTicks: Long,
        elapsedMs: Long,
        cores: Int,
        clockTicksPerSec: Long = 100L,
    ): Double? {
        if (elapsedMs <= 0 || cores <= 0 || clockTicksPerSec <= 0 || nowTicks < prevTicks) return null
        val cpuSeconds = (nowTicks - prevTicks).toDouble() / clockTicksPerSec
        val pct = cpuSeconds / (elapsedMs / 1000.0) / cores * 100.0
        return pct.coerceIn(0.0, 100.0)
    }
}

/** UI strings for the resource strip; `—` when a value is unavailable. */
object CosResourceFormat {
    private const val UNKNOWN = "\u2014"

    fun cpu(percent: Double?): String =
        if (percent == null) UNKNOWN else "${Math.round(percent)}%"

    fun bytes(bytes: Long?): String =
        if (bytes == null || bytes < 0) UNKNOWN else CosRootfsInfo.formatBytes(bytes)
}

/**
 * Samples CPU and resident memory of COS processes (same uid as the app, minus app
 * processes). Call [sample] periodically; the first CPU value is null (needs a delta).
 */
class CosResourceSampler(
    private val appPid: Int,
    private val packageName: String,
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    private val pageSizeBytes: Long = 4096L,
    private val procRoot: File = File("/proc"),
) {
    data class Sample(val cpuPercent: Double?, val rssBytes: Long?)

    private var prevTicks: Long? = null
    private var prevAtMs: Long = 0L

    fun sample(nowMs: Long): Sample {
        val myUid = read(File(procRoot, "self/status"))?.let { CosProcStats.parseUid(it) }
            ?: return Sample(null, null)
        var ticks = 0L
        var rssPages = 0L
        var any = false
        procRoot.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val status = read(File(dir, "status")) ?: return@forEach
            if (CosProcStats.parseUid(status) != myUid) return@forEach
            val cmdline = read(File(dir, "cmdline")) ?: return@forEach
            if (!CosProcStats.isCosProcess(pid, cmdline, appPid, packageName)) return@forEach
            val t = read(File(dir, "stat"))?.let { CosProcStats.parseCpuTicks(it) } ?: return@forEach
            val r = read(File(dir, "statm"))?.let { CosProcStats.parseRssPages(it) } ?: return@forEach
            ticks += t
            rssPages += r
            any = true
        }
        if (!any) {
            prevTicks = null
            return Sample(null, null)
        }
        val prev = prevTicks
        val cpu = if (prev == null) null else CosProcStats.cpuPercent(prev, ticks, nowMs - prevAtMs, cores)
        prevTicks = ticks
        prevAtMs = nowMs
        return Sample(cpu, rssPages * pageSizeBytes)
    }

    private fun read(f: File): String? = runCatching { f.readText() }.getOrNull()
}
