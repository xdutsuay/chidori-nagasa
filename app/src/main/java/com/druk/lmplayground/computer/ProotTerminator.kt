package com.druk.lmplayground.computer

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Stops a running proot without orphaning its guest processes.
 *
 * SIGKILL on proot is NOT safe: Termux proot's `--kill-on-exit` only acts when the
 * initial tracee exits, and proot has no PTRACE_O_EXITKILL. A SIGKILLed tracer
 * leaves its tracees detached, still running as the app uid, without path
 * translation (so they would see the real Android filesystem).
 *
 * Escalation (logged under tag `COS`):
 *  1. Snapshot proot's descendant tree from `/proc` (pid + start time).
 *  2. SIGQUIT to proot: its handler runs `kill_all_tracees` (SIGKILL every tracee) and exits.
 *  3. Wait [graceMs] (default ~2 s) for proot to exit.
 *  4. Still alive → `destroyForcibly()` (SIGKILL) on proot.
 *  5. SIGKILL every snapshotted descendant still alive (same pid AND start time, so
 *     no recycled pid is hit; zombies are skipped). This is the backstop for step 4.
 */
internal class ProotTerminator(
    private val sendSignal: (pid: Int, signal: Int) -> Unit,
    private val graceMs: Long,
    private val log: (String) -> Unit,
    private val procRoot: File = File("/proc"),
    private val resolvePid: (Process) -> Int = ::pidOf,
) {

    fun terminate(proc: Process, reason: String) {
        val pid = resolvePid(proc).takeIf { it > 0 }
            ?: soleProotChild()?.also { log("terminate[$reason]: pid via /proc scan: $it") }
            ?: -1
        if (pid <= 0) {
            log("terminate[$reason]: proot pid unknown; destroyForcibly")
            proc.destroyForcibly()
            return
        }
        val tree = LinkedHashMap<Int, Long>()
        ProcTree.descendants(procRoot, pid).forEach { tree[it.pid] = it.startTime }
        if (proc.isAlive) {
            log("terminate[$reason]: SIGQUIT -> proot pid=$pid (guest tree=${tree.size})")
            runCatching { sendSignal(pid, SIGQUIT) }
                .onFailure { log("terminate[$reason]: SIGQUIT failed: ${it.message}") }
            val exited = runCatching { proc.waitFor(graceMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (exited) {
                log("terminate[$reason]: proot exited after SIGQUIT")
            } else {
                // Late forks during the grace period.
                ProcTree.descendants(procRoot, pid).forEach { tree.putIfAbsent(it.pid, it.startTime) }
                log("terminate[$reason]: grace ${graceMs}ms expired; destroyForcibly pid=$pid")
                proc.destroyForcibly()
                runCatching { proc.waitFor(1_000, TimeUnit.MILLISECONDS) }
            }
        }
        val survivors = tree.filter { (p, start) -> ProcTree.isAliveSame(procRoot, p, start) }.keys
        survivors.forEach { p -> runCatching { sendSignal(p, SIGKILL) } }
        if (survivors.isNotEmpty()) {
            log("terminate[$reason]: SIGKILL ${survivors.size} surviving guest process(es): $survivors")
        }
    }

    companion object {
        const val SIGQUIT = 3
        const val SIGKILL = 9
        const val DEFAULT_GRACE_MS = 2_000L

        /** Process pid on Android (field `pid` in ProcessImpl/UNIXProcess) or JVM 9+ `pid()`. */
        /**
         * Fallback when reflection can't read the pid: the only live child of this
         * process whose argv0/argv1 is `libproot.so`. Null if there are zero or several
         * (never guess between concurrent commands).
         */
        private fun ProotTerminator.soleProotChild(): Int? = runCatching {
            val me = File(procRoot, "self").canonicalFile.name.toInt()
            ProcTree.all(procRoot)
                .filter { it.ppid == me && it.state != 'Z' && it.state != 'X' }
                .filter { p ->
                    File(procRoot, "${p.pid}/cmdline").readText().split('\u0000').take(2)
                        .any { it.endsWith("/${ProotCommand.PROOT_LIB}") }
                }
                .singleOrNull()?.pid
        }.getOrNull()

        fun pidOf(proc: Process): Int {
            runCatching {
                // Public Process.pid() (JDK 9+; reflective so it links on Android, where
                // the impl class has a private `pid` field instead).
                val m = Process::class.java.getMethod("pid")
                return (m.invoke(proc) as Number).toInt()
            }
            var c: Class<*>? = proc.javaClass
            while (c != null) {
                runCatching {
                    val f = c.getDeclaredField("pid")
                    f.isAccessible = true
                    return f.getInt(proc)
                }
                c = c.superclass
            }
            return -1
        }
    }
}

/** Minimal `/proc` reader: ppid tree + start times (works for same-uid processes on Android). */
internal object ProcTree {
    data class Proc(val pid: Int, val ppid: Int, val state: Char, val startTime: Long)

    /** Parses `/proc/<pid>/stat`; comm may contain spaces/parens, so split after the last ')'. */
    fun parseStat(pid: Int, stat: String): Proc? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val f = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // f[0]=state(3) f[1]=ppid(4) … f[19]=starttime(22)
        if (f.size < 20) return null
        return Proc(pid, f[1].toIntOrNull() ?: return null, f[0].firstOrNull() ?: '?', f[19].toLongOrNull() ?: 0L)
    }

    fun read(procRoot: File, pid: Int): Proc? =
        runCatching { parseStat(pid, File(procRoot, "$pid/stat").readText()) }.getOrNull()

    fun all(procRoot: File): List<Proc> =
        procRoot.listFiles().orEmpty().mapNotNull { d -> d.name.toIntOrNull()?.let { read(procRoot, it) } }

    fun descendants(procRoot: File, root: Int): List<Proc> {
        val byParent = all(procRoot).groupBy { it.ppid }
        val out = ArrayList<Proc>()
        val queue = ArrayDeque(listOf(root))
        val seen = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (child in byParent[p].orEmpty()) {
                if (seen.add(child.pid)) {
                    out += child
                    queue += child.pid
                }
            }
        }
        return out
    }

    /** Same process (pid not recycled) and not a zombie. */
    fun isAliveSame(procRoot: File, pid: Int, startTime: Long): Boolean {
        val p = read(procRoot, pid) ?: return false
        return p.startTime == startTime && p.state != 'Z' && p.state != 'X'
    }
}
