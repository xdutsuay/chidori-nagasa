package com.druk.lmplayground.conversation

import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.tools.CosExecFormatter
import com.druk.lmplayground.tools.CosExecTool
import com.druk.lmplayground.tools.CosToolDefaults
import com.druk.lmplayground.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * CP1 r5: a chat Stop during cos_exec must leave exit_code 130 / cancelled:true in
 * the session history and on the tool card, and the next turn must not start
 * after an orphan tool call (device bug: LFM2.5 350M then answered "Say hello"
 * with `{"exit_code":0,"stdout":"","stderr":""}`).
 */
@RunWith(RobolectricTestRunner::class)
class StoppedToolRoundTest {

    /**
     * Mirror of the native session's message list (LlamaGenerationSession.cpp):
     * generate() with rc=2 appends an assistant message carrying the tool calls,
     * submitToolResults() appends one "tool" message per result, addMessage()
     * appends the user message, and the next prompt is rendered from that list.
     */
    private class FakeNativeHistory {
        data class Msg(val role: String, val content: String, val toolCallIds: List<String> = emptyList(), val toolCallId: String? = null)
        val messages = mutableListOf<Msg>()
        val submitted = mutableListOf<String>()

        fun modelEmittedToolCalls(callsJson: String) {
            val arr = JSONArray(callsJson)
            messages += Msg("assistant", "", (0 until arr.length()).map { arr.getJSONObject(it).getString("id") })
        }

        fun submitToolResults(resultsJson: String): Int {
            submitted += resultsJson
            val arr = JSONArray(resultsJson)
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                messages += Msg("tool", r.getString("content"), toolCallId = r.getString("id"))
            }
            return 0
        }

        fun addMessage(user: String) { messages += Msg("user", user) }

        /** Tool-call ids with no matching "tool" message (an orphan tool call). */
        fun orphanToolCalls(): List<String> {
            val answered = messages.mapNotNull { it.toolCallId }.toSet()
            return messages.flatMap { it.toolCallIds }.filter { it !in answered }
        }

        fun renderPrompt(): String = messages.joinToString("\n") { "<|${it.role}|>${it.content}" }
    }

    private fun cosCall(id: String = "call_0", command: String = "sleep 60") = JSONArray().put(
        JSONObject().put("id", id).put("name", CosToolDefaults.COS_EXEC)
            .put("arguments", JSONObject().put("command", command).toString()),
    ).toString()

    private fun contentOf(resultsJson: String, id: String): JSONObject {
        val arr = JSONArray(resultsJson)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) return JSONObject(o.getString("content"))
        }
        error("no result for $id in $resultsJson")
    }

    /** Runs one tool round like GenerationCoordinator and presses Stop once the command is running. */
    private fun stopDuringCosExec(
        runner: suspend (String) -> ComputerExecResult,
        started: CountDownLatch,
        history: FakeNativeHistory,
        shown: AtomicReference<List<ToolCallInfo>?>,
    ): Long = runBlocking {
        val registry = ToolRegistry().apply {
            register(CosExecTool(runner))
            setToolEnabled(CosToolDefaults.COS_EXEC, true)
        }
        val calls = cosCall()
        history.modelEmittedToolCalls(calls)
        val turn = launch(Dispatchers.Default) {
            ToolCallRunner.run(registry, calls, pollMs = 50) { cancelled ->
                StoppedToolRound.record(calls, cancelled, 1_000, submit = history::submitToolResults, show = { shown.set(it) })
            }
        }
        assertTrue("command never started", started.await(5, TimeUnit.SECONDS))
        val t0 = System.currentTimeMillis()
        turn.cancelAndJoin() // == ConversationViewModel.cancelGeneration()
        System.currentTimeMillis() - t0
    }

    @Test
    fun `after Stop the recorded tool message has exit 130 and cancelled true`() {
        val started = CountDownLatch(1)
        val history = FakeNativeHistory()
        val shown = AtomicReference<List<ToolCallInfo>?>(null)
        val elapsed = stopDuringCosExec({ started.countDown(); runInterruptible { Thread.sleep(60_000) }; ComputerExecResult(0, "", "") }, started, history, shown)
        assertTrue("Stop took ${elapsed}ms", elapsed < 5_000)

        assertEquals("results submitted to the session exactly once", 1, history.submitted.size)
        val content = contentOf(history.submitted.single(), "call_0")
        assertEquals(130, content.getInt("exit_code"))
        assertTrue(content.getBoolean("cancelled"))
        assertEquals(CosExecFormatter.CANCELLED_MESSAGE, content.getString("error"))

        val card = shown.get()!!.single()
        assertEquals(CosToolDefaults.COS_EXEC, card.name)
        assertTrue("tool card must be marked stopped", card.stopped)
        assertEquals(130, JSONObject(card.result).getInt("exit_code"))
    }

    @Test
    fun `next turn history contains the cancelled result, not exit 0, and no orphan tool call`() {
        val started = CountDownLatch(1)
        val history = FakeNativeHistory()
        stopDuringCosExec({ started.countDown(); runInterruptible { Thread.sleep(60_000) }; ComputerExecResult(0, "", "") }, started, history, AtomicReference(null))

        history.addMessage("Say hello in one short sentence.")
        assertEquals(emptyList<String>(), history.orphanToolCalls())
        val roles = history.messages.map { it.role }
        assertEquals(listOf("assistant", "tool", "user"), roles)
        val prompt = history.renderPrompt()
        assertTrue(prompt, prompt.contains("\"exit_code\":130"))
        assertTrue(prompt, prompt.contains("\"cancelled\":true"))
        assertFalse(prompt, prompt.contains("\"exit_code\":0"))
    }

    @Test
    fun `without recording, the next turn follows an orphan tool call (the device bug)`() {
        val history = FakeNativeHistory()
        history.modelEmittedToolCalls(cosCall())
        history.addMessage("Say hello in one short sentence.")
        assertEquals(listOf("call_0"), history.orphanToolCalls())
    }

    @Test
    fun `exit 0 returned by the killed process after Stop does not replace the cancelled result`() {
        val started = CountDownLatch(1)
        val history = FakeNativeHistory()
        // Runner ignores interruption and returns a "normal" exit 0 after Stop.
        val elapsed = stopDuringCosExec({
            started.countDown()
            val until = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < until) { try { Thread.sleep(50) } catch (_: InterruptedException) {} }
            ComputerExecResult(0, "", "")
        }, started, history, AtomicReference(null))
        assertTrue("Stop took ${elapsed}ms", elapsed < 5_000)
        val content = contentOf(history.submitted.single(), "call_0")
        assertEquals(130, content.getInt("exit_code"))
        assertTrue(content.getBoolean("cancelled"))
    }

    @Test
    fun `complete fills calls without a result with cancelled content in call order`() {
        val calls = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "web_search").put("arguments", "{}"))
            .put(JSONObject().put("id", "b").put("name", CosToolDefaults.COS_EXEC).put("arguments", "{}"))
            .put(JSONObject().put("id", "c").put("name", "web_fetch").put("arguments", "{}"))
            .toString()
        val partial = JSONArray().put(JSONObject().put("id", "a").put("name", "web_search").put("content", "hits")).toString()
        val out = JSONArray(StoppedToolRound.complete(calls, partial))
        assertEquals(3, out.length())
        assertEquals(listOf("a", "b", "c"), (0 until 3).map { out.getJSONObject(it).getString("id") })
        assertEquals("hits", out.getJSONObject(0).getString("content"))
        assertEquals(CosExecFormatter.cancelledJson(), out.getJSONObject(1).getString("content"))
        assertEquals(ToolRegistry.CANCELLED_ERROR, out.getJSONObject(2).getString("content"))

        val none = JSONArray(StoppedToolRound.complete(calls, null))
        assertEquals(3, none.length())
        assertEquals(130, JSONObject(none.getJSONObject(1).getString("content")).getInt("exit_code"))
        val limit = JSONArray(StoppedToolRound.complete(calls, "not json") { StoppedToolRound.ROUND_LIMIT_ERROR })
        assertEquals(StoppedToolRound.ROUND_LIMIT_ERROR, limit.getJSONObject(1).getString("content"))
    }

    @Test
    fun `record still shows the card when submitting to the session fails`() {
        val shown = AtomicReference<List<ToolCallInfo>?>(null)
        val rc = StoppedToolRound.record(cosCall(), StoppedToolRound.complete(cosCall(), null), 0,
            submit = { error("service gone") }, show = { shown.set(it) })
        assertEquals(null, rc)
        assertNotNull(shown.get())
        assertTrue(shown.get()!!.single().stopped)
    }

    @Test
    fun `tool row Stopped flag follows the result`() {
        assertTrue(ToolCallInfoMapper.isStoppedResult(CosExecFormatter.cancelledJson()))
        assertTrue(ToolCallInfoMapper.isStoppedResult(ToolRegistry.CANCELLED_ERROR))
        assertFalse(ToolCallInfoMapper.isStoppedResult("""{"exit_code":0,"stdout":"","stderr":""}"""))
        assertFalse(ToolCallInfoMapper.isStoppedResult(ToolRegistry.disabledError(CosToolDefaults.COS_EXEC)))
        assertFalse(ToolCallInfoMapper.isStoppedResult(StoppedToolRound.ROUND_LIMIT_ERROR))
        assertFalse(ToolCallInfoMapper.isStoppedResult("plain text"))
        assertFalse(ToolCallInfoMapper.isStoppedResult(""))
        val info = ToolCallInfoMapper.buildToolCallInfoList(
            cosCall(), JSONArray().put(JSONObject().put("id", "call_0").put("content", """{"exit_code":0,"stdout":"","stderr":""}""")).toString(), 10,
        ).single()
        assertFalse(info.stopped)
    }
}
