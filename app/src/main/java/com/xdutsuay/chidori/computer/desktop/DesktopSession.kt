package com.xdutsuay.chidori.computer.desktop

import com.xdutsuay.chidori.computer.LocalComputerRuntime
import com.xdutsuay.chidori.computer.ProcTree
import com.xdutsuay.chidori.computer.ProotCommand
import com.xdutsuay.chidori.computer.ProotTerminator
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Supervisor for one desktop session: the [DesktopCommand.launchScript] run as ONE proot
 * process tree via [LocalComputerRuntime.launchSupervised], and torn down through the
 * same [ProotTerminator] path as terminal commands (SIGQUIT → grace → force kill →
 * `/proc` sweep). Blocking API; [LocalDesktopBridge] calls it on an IO thread.
 *
 * COS log lines (logcat tag `COS`):
 * - `desktop start display=:N size=WxHxD seccomp=on|off`
 * - `desktop stale lock/socket cleaned display=:N` (only if one was left behind)
 * - `desktop ready display=:N pid=<proot pid> in <ms>ms`
 * - `desktop error: <message>` (start failed) / `desktop exited unexpectedly: <message>`
 * - `desktop stop reason=<r> display=:N tree=<n>` then the `terminate[desktop-stop]: …`
 *   lines, then `desktop stopped display=:N survivors=0`
 */
internal class DesktopSession(
    private val runtime: LocalComputerRuntime,
    val config: DesktopConfig,
    private val log: (String) -> Unit,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
    private val procRoot: File = File("/proc"),
    /** Called once if the session dies on its own after it was ready. */
    private val onUnexpectedExit: (String) -> Unit = {},
) {
    sealed interface Outcome {
        data class Ready(val ready: DesktopCommand.Ready, val prootPid: Int) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private class Attempt(val handle: ProotTerminator.Handle) {
        val result = CompletableFuture<Outcome>()
        val stderrTail = ArrayDeque<String>()
        @Volatile var stopping = false
        @Volatile var ready = false

        @Synchronized fun addErr(line: String) {
            stderrTail.addLast(line)
            while (stderrTail.size > 20) stderrTail.removeFirst()
        }

        @Synchronized fun errText(): String = stderrTail.joinToString("\n")
    }

    @Volatile private var current: Attempt? = null

    val isAlive: Boolean get() = current?.handle?.proc?.isAlive == true

    val prootPid: Int get() = current?.handle?.pid ?: -1

    /** Starts the session; blocks until ready, failed or [readyTimeoutMs]. */
    @Synchronized
    fun start(): Outcome {
        check(current == null) { "session already started" }
        runtime.supervisedBlockReason()?.let { return fail(it) }
        val noSeccomp = runtime.prootNoSeccomp()
        val first = attempt(noSeccomp)
        if (first is Outcome.Failed && !noSeccomp && current == null && lastExitSeccompSignature) {
            log("desktop: proot failed with seccomp; retrying with PROOT_NO_SECCOMP=1")
            val second = attempt(true)
            if (second is Outcome.Ready) runtime.rememberProotNoSeccomp()
            return second
        }
        return first
    }

    @Volatile private var lastExitSeccompSignature = false

    private fun fail(msg: String): Outcome.Failed {
        log("desktop error: $msg")
        return Outcome.Failed(msg)
    }

    private fun attempt(noSeccomp: Boolean): Outcome {
        lastExitSeccompSignature = false
        log(
            "desktop start display=${config.displayName} size=${config.width}x${config.height}x${config.depth} " +
                "seccomp=${if (noSeccomp) "off" else "on"}",
        )
        val started = System.currentTimeMillis()
        val attemptRef = AtomicReference<Attempt?>(null)
        val pending = ArrayList<String>()
        val onLine: (String) -> Unit = { line ->
            val queued = synchronized(pending) {
                if (attemptRef.get() == null) pending.add(line) else false
            }
            if (!queued) attemptRef.get()?.let { handleLine(it, line) }
        }
        val handle = try {
            runtime.launchSupervised(DesktopCommand.sessionArgv(config), noSeccomp, onLine)
        } catch (t: Throwable) {
            return fail("cannot start proot: ${t.message ?: t.javaClass.simpleName}")
        }
        val a = Attempt(handle)
        synchronized(pending) {
            attemptRef.set(a)
            pending.forEach { handleLine(a, it) }
            pending.clear()
        }
        current = a
        Thread({ watchExit(a) }, "cos-desktop-exit").apply { isDaemon = true }.start()

        val outcome = try {
            a.result.get(readyTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Outcome.Failed("desktop not ready after ${readyTimeoutMs / 1000}s")
        } catch (t: Throwable) {
            Outcome.Failed("desktop start failed: ${t.message ?: t.javaClass.simpleName}")
        }
        return when (outcome) {
            is Outcome.Ready -> {
                a.ready = true
                log(
                    "desktop ready display=${outcome.ready.display} pid=${handle.pid} " +
                        "in ${System.currentTimeMillis() - started}ms",
                )
                outcome
            }
            is Outcome.Failed -> {
                if (handle.proc.isAlive) terminate(a, "desktop-start-failed")
                current = null
                fail(outcome.message)
            }
        }
    }

    private fun handleLine(a: Attempt, line: String) {
        if (line.startsWith("[err] ")) {
            a.addErr(line.removePrefix("[err] "))
            return
        }
        DesktopCommand.parseReady(line)?.let { r ->
            a.result.complete(Outcome.Ready(r, a.handle.pid))
            return
        }
        DesktopCommand.parseError(line)?.let { msg ->
            a.result.complete(Outcome.Failed(msg))
            return
        }
        if (line.startsWith(DesktopCommand.STALE_MARKER)) {
            log("desktop stale lock/socket cleaned display=${config.displayName}")
        }
    }

    private fun watchExit(a: Attempt) {
        val rc = runCatching { a.handle.proc.waitFor() }.getOrDefault(-1)
        // Let the reader threads drain the last lines (ERROR marker / proot stderr).
        Thread.sleep(200)
        val err = a.errText()
        lastExitSeccompSignature = ProotCommand.shouldRetryWithoutSeccomp(rc, err, alreadyNoSeccomp = false)
        val msg = "session exited rc=$rc" + (err.lineSequence().lastOrNull { it.isNotBlank() }?.let { ": $it" } ?: "")
        if (a.result.complete(Outcome.Failed(msg))) return // exited before ready
        if (!a.stopping && a.ready) {
            if (current === a) current = null
            log("desktop exited unexpectedly: $msg")
            onUnexpectedExit(msg)
        }
    }

    /**
     * Stops the whole tree (blocking, ~[ProotTerminator.DEFAULT_GRACE_MS] at most plus
     * the force-kill wait) and logs how many processes of the pre-stop tree survived.
     */
    @Synchronized
    fun stop(reason: String = "desktop-stop") {
        val a = current ?: return
        current = null
        terminate(a, reason)
    }

    private fun terminate(a: Attempt, reason: String) {
        a.stopping = true
        val pid = a.handle.pid
        val tree = if (pid > 0) {
            runCatching { ProcTree.descendants(procRoot, pid) }.getOrDefault(emptyList()) +
                listOfNotNull(ProcTree.read(procRoot, pid))
        } else {
            emptyList()
        }
        log("desktop stop reason=$reason display=${config.displayName} tree=${tree.size}")
        runtime.terminateSupervised(a.handle, reason)
        runCatching { a.handle.proc.waitFor(2, TimeUnit.SECONDS) }
        val survivors = tree.filter { ProcTree.isAliveSame(procRoot, it.pid, it.startTime) }.map { it.pid }
        log("desktop stopped display=${config.displayName} survivors=${survivors.size}" +
            if (survivors.isEmpty()) "" else " $survivors")
    }

    companion object {
        /** Xvfb readiness is polled for 30 s inside the script; this covers proot start + that. */
        const val READY_TIMEOUT_MS = 60_000L
    }
}
