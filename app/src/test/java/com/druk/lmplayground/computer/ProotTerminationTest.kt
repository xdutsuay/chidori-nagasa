package com.druk.lmplayground.computer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.conversation.StoppedToolRound
import com.druk.lmplayground.conversation.ToolCallRunner
import com.druk.lmplayground.tools.CosExecTool
import com.druk.lmplayground.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * Review B10: timeout / Stop must not SIGKILL proot first (that orphans guest
 * processes outside proot). Uses the fake `libproot.so` from
 * [LocalComputerRuntimeProotTest] in "hang" mode: it forks a background `sleep 300`
 * (the "guest") and either honours SIGQUIT like proot's kill_all_tracees, or ignores it.
 */
@RunWith(RobolectricTestRunner::class)
class ProotTerminationTest {
    private lateinit var context: Context
    private lateinit var flags: ComputerFlagStore
    private lateinit var tmp: File
    private lateinit var libDir: File
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val signals: MutableList<Pair<Int, Int>> = Collections.synchronizedList(mutableListOf())
    private val payload = "fake alpine 3.24.3 tarball".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        assumeFalse(
            "fake libproot / sleep /proc fakes need a POSIX host (master plan: skip-on-Windows)",
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
        )
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        flags = ComputerFlagStore.fromSharedPreferences(prefs)
        flags.setComputerEnabled(true)
        CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        tmp = Files.createTempDirectory("fakelib-kill").toFile()
        libDir = File(tmp, "lib/x86_64").apply { mkdirs() }
        ProotCommand.REQUIRED_LIBS.forEach { File(libDir, it).writeText("") }
        File(libDir, ProotCommand.PROOT_LIB).apply {
            writeText(LocalComputerRuntimeProotTest.FAKE_PROOT)
            setExecutable(true)
        }
        File(libDir, "hang").writeText("1")
    }

    @After
    fun tearDown() {
        // assumeFalse in setUp must not trip UninitializedPropertyAccessException here.
        if (::tmp.isInitialized) {
            runCatching {
                childPid()?.let { if (alive(it)) ProcessBuilder("kill", "-9", "$it").start().waitFor() }
            }
            CosFiles.deleteTree(tmp)
        }
        if (::context.isInitialized) {
            CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
        }
    }

    private fun runtime(timeoutMs: Long, launchPidOf: (Process) -> Int = ProotTerminator::pidOf) = LocalComputerRuntime(
        context = context,
        flags = flags,
        bootstrap = CosRootfsBootstrap(
            fetch = { _, dest, _ -> dest.writeBytes(payload); CosProvisioning.Outcome(true, "ok") },
            extract = { _, dest ->
                File(dest, "bin").mkdirs()
                File(dest, "etc").mkdirs()
                CosProvisioning.Outcome(true, "extracted")
            },
            expectedSha256For = { payloadSha },
        ),
        nativeLibDirOverride = libDir.absolutePath,
        log = { events += "log:$it" },
        execTimeoutMs = timeoutMs,
        killGraceMs = GRACE_MS,
        sendSignal = { pid, sig ->
            signals += pid to sig
            events += "signal:$sig"
            ProcessBuilder("kill", "-$sig", "$pid").start().waitFor()
        },
        launchPidOf = launchPidOf,
    )

    private fun childPid(): Int? =
        File(libDir, "child.pid").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    private fun alive(pid: Int): Boolean {
        val stat = ProcTree.read(File("/proc"), pid) ?: return false
        return stat.state != 'Z' && stat.state != 'X'
    }

    private fun idx(prefix: String) = events.indexOfFirst { it.startsWith(prefix) }

    @Test
    fun `timeout sends SIGQUIT first and proot's handler takes the guest down`() = runBlocking {
        val rt = runtime(timeoutMs = 1_500)
        rt.ensureRootfs()
        val r = rt.execStreaming(listOf("/bin/sh", "-c", "sleep 300 &"), "root") {}
        assertEquals(124, r.exitCode)
        assertEquals("events=$events", ProotTerminator.SIGQUIT, signals.firstOrNull()?.second)
        assertTrue(events.any { it.contains("proot exited after SIGQUIT") })
        assertFalse(events.any { it.contains("destroyForcibly") })
        // pid captured when proot started; no later /proc guess.
        assertTrue("events=$events", events.any { it.contains("(launch: reflection)") })
        assertTrue("events=$events", events.any { it.startsWith("log:terminate[timeout]: pid via launch: ${signals.first().first}") })
        assertFalse(events.any { it.contains("pid via /proc scan") })
        assertTrue(File(libDir, "events").readText().contains("QUIT"))
        val child = childPid()!!
        assertFalse("guest child $child survived", alive(child))
    }

    @Test
    fun `proot ignoring SIGQUIT escalates to destroyForcibly after grace then sweeps orphans`() = runBlocking {
        File(libDir, "ignore_quit").writeText("1")
        val rt = runtime(timeoutMs = 1_500)
        rt.ensureRootfs()
        val started = System.currentTimeMillis()
        val r = rt.execStreaming(listOf("/bin/sh", "-c", "sleep 300 &"), "root") {}
        val elapsed = System.currentTimeMillis() - started
        assertEquals(124, r.exitCode)
        val child = childPid()!!
        assertTrue("events=$events", signals.isNotEmpty())
        val prootPid = signals.first().first
        // Order: SIGQUIT to proot -> grace expired/destroyForcibly -> SIGKILL to orphaned guest.
        assertEquals(prootPid to ProotTerminator.SIGQUIT, signals[0])
        val quit = idx("signal:3")
        val force = idx("log:terminate[timeout]: grace ${GRACE_MS}ms expired; destroyForcibly")
        val sweep = idx("signal:9")
        assertTrue("events=$events", quit in 0 until force)
        assertTrue("events=$events", force < sweep)
        assertTrue(signals.contains(child to ProotTerminator.SIGKILL))
        assertNotEquals(prootPid, child)
        assertTrue(elapsed >= 1_500 + GRACE_MS)
        Thread.sleep(200)
        assertFalse("guest child $child survived", alive(child))
    }

    @Test
    fun `stop uses the same SIGQUIT-first escalation`() = runBlocking {
        val rt = runtime(timeoutMs = 60_000)
        rt.ensureRootfs()
        var result: ComputerExecResult? = null
        val t = Thread {
            result = runBlocking { rt.execStreaming(listOf("/bin/sh", "-c", "sleep 300"), "root") {} }
        }.also { it.start() }
        val deadline = System.currentTimeMillis() + 10_000
        while (childPid() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Thread.sleep(200)
        rt.stop()
        t.join(15_000)
        assertFalse(t.isAlive)
        assertNotEquals(124, result!!.exitCode)
        assertEquals("events=$events", ProotTerminator.SIGQUIT, signals.firstOrNull()?.second)
        assertTrue(events.any { it.startsWith("log:terminate[stop]: SIGQUIT") })
        assertFalse(events.any { it.contains("destroyForcibly") })
        assertFalse(alive(childPid()!!))
        assertFalse(rt.status() == ComputerStatus.Running)
    }

    @Test
    fun `pid captured at launch via proc diff when reflection fails`() = runBlocking {
        val rt = runtime(timeoutMs = 1_500, launchPidOf = { -1 })
        rt.ensureRootfs()
        val r = rt.execStreaming(listOf("/bin/sh", "-c", "sleep 300 &"), "root") {}
        assertEquals(124, r.exitCode)
        val started = events.firstOrNull { it.contains("(launch: /proc diff") }
        assertTrue("events=$events", started != null)
        val pid = Regex("pid=(\\d+)").find(started!!)!!.groupValues[1].toInt()
        assertTrue("events=$events", events.any { it == "log:terminate[timeout]: pid via launch: $pid" })
        assertEquals(pid to ProotTerminator.SIGQUIT, signals.first())
        assertFalse(events.any { it.contains("pid via /proc scan") })
        assertTrue(events.any { it.contains("proot exited after SIGQUIT") })
        assertFalse(alive(childPid()!!))
    }

    @Test
    fun `backstop kill list includes reparented tracees via TracerPid`() {
        // Fake /proc: proot=4242; 500 is its ppid-child; 600 double-forked (ppid 1) but is
        // still traced by proot; 700 is unrelated. proot "ignores" SIGQUIT (signals are only
        // recorded), so the backstop sweep runs against the fake tree.
        val fake = File(tmp, "proc").apply { mkdirs() }
        fun proc(pid: Int, ppid: Int, tracer: Int) {
            File(fake, "$pid").mkdirs()
            File(fake, "$pid/stat").writeText("$pid (sh) S $ppid $pid $pid 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 ${1000 + pid} 0 0")
            File(fake, "$pid/status").writeText("Name:\tsh\nState:\tS\nTracerPid:\t$tracer\nUid:\t1\n")
        }
        proc(4242, 99, 0); proc(500, 4242, 4242); proc(600, 1, 4242); proc(700, 1, 0)
        val real = ProcessBuilder("sleep", "30").start()
        val sent = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val logs = Collections.synchronizedList(mutableListOf<String>())
        ProotTerminator(
            sendSignal = { pid, sig -> sent += pid to sig },
            graceMs = 200,
            log = { logs += it },
            procRoot = fake,
        ).terminate(ProotTerminator.Handle(real, 4242), "test")
        assertEquals("logs=$logs", 4242 to ProotTerminator.SIGQUIT, sent.first())
        assertTrue("logs=$logs", logs.any { it.contains("guest tree=2") })
        assertEquals(setOf(500, 600), sent.filter { it.second == ProotTerminator.SIGKILL }.map { it.first }.toSet())
        assertFalse(real.isAlive)
    }

    @Test
    fun `already exited proot is not snapshotted or signalled`() {
        val real = ProcessBuilder("true").start().apply { waitFor() }
        val sent = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>()
        ProotTerminator({ p, s -> sent += p to s }, 200, { logs += it })
            .terminate(ProotTerminator.Handle(real, 4242), "stop")
        assertTrue(sent.isEmpty())
        assertTrue(logs.any { it.contains("proot already exited") })
    }

    @Test
    fun `pid fallback via proc scan when reflection fails`() {
        val proc = ProcessBuilder(File(libDir, ProotCommand.PROOT_LIB).absolutePath, "-r", "x")
            .redirectErrorStream(true).start()
        val deadline = System.currentTimeMillis() + 10_000
        while (childPid() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val sent = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val logs = Collections.synchronizedList(mutableListOf<String>())
        ProotTerminator(
            sendSignal = { pid, sig -> sent += pid to sig; ProcessBuilder("kill", "-$sig", "$pid").start().waitFor() },
            graceMs = GRACE_MS,
            log = { logs += it },
            resolvePid = { -1 },
        ).terminate(ProotTerminator.Handle(proc, -1), "test")
        assertTrue("logs=$logs", logs.any { it.contains("pid via /proc scan") })
        assertEquals("logs=$logs", ProotTerminator.SIGQUIT, sent.firstOrNull()?.second)
        assertFalse(proc.isAlive)
        Thread.sleep(200)
        assertFalse(alive(childPid()!!))
    }

    private fun waitForGuest() {
        val deadline = System.currentTimeMillis() + 10_000
        while (childPid() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("fake guest never started; events=$events", childPid() != null)
    }

    @Test
    fun `cancelling a running cos_exec coroutine terminates proot via ProotTerminator`() = runBlocking {
        val rt = runtime(timeoutMs = 120_000) // well beyond the test: must not wait for it
        var result: ComputerExecResult? = null
        val job = launch(Dispatchers.IO) { result = CosAgentBridge.exec(rt, "sleep 300") }
        waitForGuest()
        val child = childPid()!!
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("cancel took ${elapsed}ms; events=$events", elapsed < 5_000)
        assertEquals(null, result) // cancellation propagated, no fake "result"
        assertTrue("events=$events", events.contains("log:terminate reason=chat-stop (exec coroutine cancelled)"))
        assertTrue("events=$events", events.any { it.startsWith("log:terminate[chat-stop]: pid via launch: ") })
        assertEquals(ProotTerminator.SIGQUIT, signals.first().second)
        assertTrue("events=$events", events.any { it == "log:terminate[chat-stop]: proot exited after SIGQUIT" })
        Thread.sleep(200)
        assertFalse(alive(child))
    }

    @Test
    fun `chat Stop during cos_exec returns a cancelled tool result within seconds`() = runBlocking {
        val rt = runtime(timeoutMs = 120_000)
        val registry = ToolRegistry().apply {
            register(CosExecTool { command -> CosAgentBridge.exec(rt, command) })
            setToolEnabled("cos_exec", true)
        }
        val calls = JSONArray().put(
            JSONObject().put("id", "c1").put("name", "cos_exec").put("arguments", """{"command":"sleep 300"}"""),
        ).toString()
        val shown = AtomicReference<String?>(null)
        val card = AtomicReference<List<com.druk.lmplayground.conversation.ToolCallInfo>?>(null)
        // Same runner + recording GenerationCoordinator uses; cancelling it ==
        // ConversationViewModel.cancelGeneration().
        val turn = launch(Dispatchers.Default) {
            ToolCallRunner.run(registry, calls) { results ->
                StoppedToolRound.record(calls, results, 0, submit = { shown.set(it); 0 }, show = { card.set(it) })
            }
        }
        waitForGuest()
        val child = childPid()!!
        val t0 = System.currentTimeMillis()
        turn.cancelAndJoin()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("Stop took ${elapsed}ms; events=$events", elapsed < 5_000)
        val content = JSONObject(JSONArray(shown.get()!!).getJSONObject(0).getString("content"))
        assertTrue(content.toString(), content.getBoolean("cancelled"))
        assertTrue(content.toString(), content.getString("error").startsWith("cancelled"))
        assertEquals(130, content.getInt("exit_code"))
        assertTrue("tool card marked stopped", card.get()!!.single().stopped)
        assertTrue("events=$events", events.contains("log:terminate reason=chat-stop (exec coroutine cancelled)"))
        assertEquals(ProotTerminator.SIGQUIT, signals.first().second)
        Thread.sleep(200)
        assertFalse(alive(child))
    }

    @Test
    fun `cancel with SIGQUIT ignored still force-kills within grace`() = runBlocking {
        File(libDir, "ignore_quit").writeText("1")
        val rt = runtime(timeoutMs = 120_000)
        val job = launch(Dispatchers.IO) { CosAgentBridge.exec(rt, "sleep 300") }
        waitForGuest()
        val child = childPid()!!
        val t0 = System.currentTimeMillis()
        job.cancelAndJoin()
        val elapsed = System.currentTimeMillis() - t0
        assertTrue("cancel took ${elapsed}ms; events=$events", elapsed < 6_000)
        assertTrue("events=$events", events.any { it.startsWith("log:terminate[chat-stop]: grace ${GRACE_MS}ms expired") })
        Thread.sleep(200)
        assertFalse(alive(child))
    }

    private fun fakeProc(root: File, pid: Int, ppid: Int, cmdline: String) {
        File(root, "$pid").mkdirs()
        File(root, "$pid/stat").writeText("$pid (x) S $ppid $pid $pid 0 -1 0 0 0 0 0 0 0 0 0 20 0 1 0 ${5000 + pid} 0 0")
        File(root, "$pid/cmdline").writeText(cmdline)
    }

    private fun fakeProcRootWithSelf(): File {
        val root = File(tmp, "proc-diff").apply { mkdirs() }
        fakeProc(root, 1000, 1, "app_process\u0000")
        Files.createSymbolicLink(File(root, "self").toPath(), File(root, "1000").toPath())
        return root
    }

    @Test
    fun `proc diff with zero candidates is retried once after 100ms`() {
        val root = fakeProcRootWithSelf()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val term = ProotTerminator({ _, _ -> }, 200, { logs += it }, procRoot = root, resolvePid = { -1 })
        val real = ProcessBuilder("sleep", "30").start()
        try {
            val h = term.launch {
                // proot's exec lands ~40ms after start(): the first diff sees nothing.
                Thread {
                    Thread.sleep(40)
                    fakeProc(root, 2000, 1000, "/data/app/lib/libproot.so\u0000-r\u0000x\u0000")
                }.start()
                real
            }
            assertEquals("logs=$logs", 2000, h.pid)
            assertTrue("logs=$logs", logs.contains("proot started pid=2000 (launch: /proc diff, after ${ProotTerminator.DIFF_RETRY_MS}ms retry)"))
        } finally {
            real.destroyForcibly()
        }
    }

    @Test
    fun `proc diff still empty after retry leaves pid uncaptured`() {
        val root = fakeProcRootWithSelf()
        val logs = mutableListOf<String>()
        val real = ProcessBuilder("sleep", "30").start()
        try {
            val t0 = System.currentTimeMillis()
            val h = ProotTerminator({ _, _ -> }, 200, { logs += it }, procRoot = root, resolvePid = { -1 })
                .launch { real }
            assertTrue(System.currentTimeMillis() - t0 >= ProotTerminator.DIFF_RETRY_MS)
            assertEquals(-1, h.pid)
            assertTrue("logs=$logs", logs.any { it.contains("pid NOT captured") && it.contains("retry found 0") })
        } finally {
            real.destroyForcibly()
        }
    }

    @Test
    fun `proc stat parsing handles spaces and parens in comm`() {
        val p = ProcTree.parseStat(42, "42 (we ird) (x)) S 7 42 42 0 -1 4194304 1 0 0 0 0 0 0 0 20 0 1 0 98765 0 0")!!
        assertEquals(7, p.ppid)
        assertEquals('S', p.state)
        assertEquals(98765L, p.startTime)
    }

    private companion object {
        const val GRACE_MS = 600L
    }
}
