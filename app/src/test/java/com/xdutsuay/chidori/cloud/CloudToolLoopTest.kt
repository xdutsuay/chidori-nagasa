package com.xdutsuay.chidori.cloud

import com.xdutsuay.chidori.tools.ToolImageAttachments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CloudToolLoop] loop rules 1-6 from the CINDI-agent handout, with fake
 * chat/executeTools/prepareImages collaborators.
 */
class CloudToolLoopTest {

    private class RecordingListener : CloudToolLoop.Listener {
        val deltas = mutableListOf<String>()
        val toolCalls = mutableListOf<Triple<String, String, Long>>()
        override fun onDelta(fullText: String) {
            deltas += fullText
        }

        override fun onToolCalls(toolCallsJson: String, resultsJson: String, durationMs: Long) {
            toolCalls += Triple(toolCallsJson, resultsJson, durationMs)
        }
    }

    /** Scripted chat rounds; each call records the request messages it received. */
    private class ScriptedChat {
        val requests = mutableListOf<List<CloudChatMessage>>()
        private val rounds = ArrayDeque<Flow<CloudStreamEvent>>()

        fun script(round: Flow<CloudStreamEvent>) {
            rounds += round
        }

        operator fun invoke(request: List<CloudChatMessage>): Flow<CloudStreamEvent> {
            requests += request
            return rounds.removeFirst()
        }
    }

    /** Two parallel calls, so per-result tool messages can be checked. */
    private val callsJson =
        """[{"id":"call_1","name":"cos_gui","arguments":"{\"action\":\"click\",\"x\":1"},""" +
            """{"id":"call_2","name":"cos_exec","arguments":"{\"command\":\"ls\"}"}]"""

    /** One call, for rounds that only need a single tool message. */
    private val singleCallJson =
        """[{"id":"call_1","name":"cos_gui","arguments":"{\"action\":\"click\",\"x\":1}"}]"""

    private fun deltaFlow(vararg events: CloudStreamEvent): Flow<CloudStreamEvent> = flow {
        events.forEach { emit(it) }
    }

    private fun toolMsgs(messages: List<CloudChatMessage>) = messages.filter { it.role == "tool" }

    private fun contentJson(message: CloudChatMessage): JSONObject = JSONObject(message.content)

    private fun echoPrepare(images: ByteArray? = null, attachedId: String? = null) =
        { resultsJson: String, _: Boolean ->
            ToolImageAttachments.Prepared(resultsJson, images, attachedId)
        }

    @Test
    fun `text-only turn returns the text with toolRounds 0 and full onDelta`() = runTest {
        val chat = ScriptedChat()
        chat.script(
            deltaFlow(
                CloudStreamEvent.Delta("Hello "),
                CloudStreamEvent.Delta("world"),
                CloudStreamEvent.Finished(CloudUsage(promptTokens = 3, completionTokens = 4)),
            ),
        )
        val listener = RecordingListener()
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { callsJson -> resultsJson(callsJson) },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "unused" },
        )
        val result = loop.run(listOf(CloudChatMessage("user", "hi")), listener)

        assertEquals("Hello world", result.text)
        assertEquals(0, result.toolRounds)
        assertEquals(3, result.usage?.promptTokens)
        assertEquals(4, result.usage?.completionTokens)
        assertEquals(listOf("Hello ", "Hello world"), listener.deltas)
        assertTrue(listener.toolCalls.isEmpty())
        assertEquals(1, chat.requests.size)
    }

    @Test
    fun `one tool round then final text appends assistant tool and result messages`() = runTest {
        val chat = ScriptedChat()
        val roundText = "checking "
        chat.script(
            deltaFlow(
                CloudStreamEvent.Delta(roundText),
                CloudStreamEvent.ToolCalls(callsJson),
                CloudStreamEvent.Finished(null),
            ),
        )
        chat.script(
            deltaFlow(
                CloudStreamEvent.Delta("All done"),
                CloudStreamEvent.Finished(CloudUsage(promptTokens = 9, completionTokens = 2)),
            ),
        )
        val secondRoundCalls = mutableListOf<String>()
        val listener = RecordingListener()
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { json ->
                secondRoundCalls += json
                resultsJson(json)
            },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "unused" },
        )
        val result = loop.run(listOf(CloudChatMessage("user", "open settings")), listener)

        assertEquals(2, chat.requests.size)
        assertEquals(callsJson, secondRoundCalls.single())
        assertEquals(1, result.toolRounds)
        assertEquals("checking All done", result.text)
        assertEquals(9, result.usage?.promptTokens)
        assertEquals(2, result.usage?.completionTokens)
        assertEquals(listOf(roundText, "checking All done"), listener.deltas)
        assertEquals(1, listener.toolCalls.size)
        assertEquals(callsJson, listener.toolCalls[0].first)
        assertTrue(listener.toolCalls[0].third >= 0)

        val followUp = chat.requests[1]
        val assistant = followUp.first { it.role == "assistant" }
        assertEquals(roundText, assistant.content)
        assertEquals(callsJson, assistant.toolCallsJson)
        val tools = toolMsgs(followUp)
        assertEquals(2, tools.size)
        assertEquals("call_1", tools[0].toolCallId)
        assertEquals("""{"exit_code":0}""", tools[0].content)
        assertEquals("call_2", tools[1].toolCallId)
        assertEquals("""{"command":"ls"}""", tools[1].content)
        assertTrue("no image without a prepared image", followUp.none { it.imagePngBase64 != null })
    }

    @Test
    fun `prepared image is attached as a user message naming the tool call id`() = runTest {
        val chat = ScriptedChat()
        chat.script(
            deltaFlow(
                CloudStreamEvent.ToolCalls(singleCallJson),
                CloudStreamEvent.Finished(null),
            ),
        )
        chat.script(deltaFlow(CloudStreamEvent.Delta("seen"), CloudStreamEvent.Finished(null)))
        val imageBytes = byteArrayOf(1, 2, 3)
        val results = resultsJson(singleCallJson)
        val listener = RecordingListener()
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { resultsJson(singleCallJson) },
            prepareImages = { _, _ -> ToolImageAttachments.Prepared(results, imageBytes, "call_1") },
            imageBytesToBase64 = { bytes -> "b64:${bytes.size}" },
        )
        val result = loop.run(listOf(CloudChatMessage("user", "look")), listener)

        val followUp = chat.requests[1]
        val imageMsg = followUp.last { it.role == "user" }
        assertEquals("b64:3", imageMsg.imagePngBase64)
        assertTrue(imageMsg.content.contains("call_1"))
        // The image user message comes after the assistant + tool messages.
        assertTrue(followUp.indexOfFirst { it.role == "tool" } < followUp.indexOf(imageMsg))
        assertEquals("seen", result.text)
    }

    @Test
    fun `provider rejecting the image round retries once without the image`() = runTest {
        val chat = ScriptedChat()
        chat.script(
            deltaFlow(CloudStreamEvent.ToolCalls(singleCallJson), CloudStreamEvent.Finished(null)),
        )
        chat.script(
            flow { throw CloudClientException(CloudError.Http(400, "image part not supported")) },
        )
        chat.script(deltaFlow(CloudStreamEvent.Delta("done"), CloudStreamEvent.Finished(null)))

        val imageBytes = byteArrayOf(9, 9)
        val results = resultsJson(singleCallJson)
        var executeCount = 0
        val prepareImages = { resultsJson: String, vision: Boolean ->
            if (vision) {
                ToolImageAttachments.Prepared(resultsJson, imageBytes, "call_1")
            } else {
                ToolImageAttachments.Prepared(withNoVisionNote(resultsJson), null, null)
            }
        }
        val listener = RecordingListener()
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { json ->
                executeCount++
                resultsJson(json)
            },
            prepareImages = prepareImages,
            imageBytesToBase64 = { "b64" },
        )
        val result = loop.run(listOf(CloudChatMessage("user", "look")), listener)

        assertEquals(3, chat.requests.size)
        assertEquals(1, executeCount)
        assertEquals("done", result.text)
        // The retried request carries no image and the tool result says the model cannot see it.
        val retried = chat.requests[2]
        assertTrue(retried.none { it.imagePngBase64 != null })
        val toolMsg = toolMsgs(retried).single()
        assertEquals(ToolImageAttachments.NO_VISION_NOTE, contentJson(toolMsg).optString("image"))
        assertTrue(
            "no-vision note mentions the model cannot see",
            ToolImageAttachments.NO_VISION_NOTE.contains("cannot see"),
        )
        // Later rounds in the turn send no images either.
        assertTrue(chat.requests.drop(2).all { req -> req.none { it.imagePngBase64 != null } })
    }

    @Test
    fun `non-image Http 400 is not retried`() = runTest {
        val chat = ScriptedChat()
        chat.script(
            deltaFlow(CloudStreamEvent.ToolCalls(callsJson), CloudStreamEvent.Finished(null)),
        )
        chat.script(
            flow { throw CloudClientException(CloudError.Http(400, "bad request")) },
        )
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { resultsJson(callsJson) },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "b64" },
        )
        val thrown = runCatching {
            loop.run(listOf(CloudChatMessage("user", "hi")), RecordingListener())
        }.exceptionOrNull()
        assertTrue(thrown is CloudClientException)
        val http = (thrown as CloudClientException).error as CloudError.Http
        assertEquals(400, http.code)
        assertEquals(2, chat.requests.size)
    }

    @Test
    fun `round limit stops a second tool round with ROUND_LIMIT results and no further request`() = runTest {
        val chat = ScriptedChat()
        // Round 1: tools are requested and executed (maxToolRounds = 1 allows one round).
        chat.script(
            deltaFlow(
                CloudStreamEvent.Delta("calling "),
                CloudStreamEvent.ToolCalls(callsJson),
                CloudStreamEvent.Finished(null),
            ),
        )
        // Round 2: the model asks for tools again — no rounds left.
        chat.script(
            deltaFlow(
                CloudStreamEvent.ToolCalls(singleCallJson),
                CloudStreamEvent.Finished(null),
            ),
        )
        var executeCount = 0
        val executedJsons = mutableListOf<String>()
        val listener = RecordingListener()
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { json ->
                executeCount++
                executedJsons += json
                resultsJson(json)
            },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "b64" },
            maxToolRounds = 1,
        )
        val result = loop.run(listOf(CloudChatMessage("user", "hi")), listener)

        assertEquals(2, chat.requests.size)
        assertEquals(1, executeCount)
        assertEquals(callsJson, executedJsons.single())

        assertEquals(2, listener.toolCalls.size)
        // Round 1 reported real results.
        val (round1Calls, round1Results, _) = listener.toolCalls[0]
        assertEquals(callsJson, round1Calls)
        val round1 = JSONArray(round1Results)
        assertEquals(2, round1.length())
        assertEquals("""{"exit_code":0}""", round1.getJSONObject(0).getString("content"))
        // Round 2 reported ROUND_LIMIT results and stopped without a third request.
        val (pendingJson, resultsJson, durationMs) = listener.toolCalls[1]
        assertEquals(singleCallJson, pendingJson)
        assertEquals(0L, durationMs)
        val results = JSONArray(resultsJson)
        assertEquals(1, results.length())
        assertEquals("call_1", results.getJSONObject(0).getString("id"))
        assertEquals("cos_gui", results.getJSONObject(0).getString("name"))
        assertEquals(
            com.xdutsuay.chidori.conversation.StoppedToolRound.ROUND_LIMIT_ERROR,
            results.getJSONObject(0).getString("content"),
        )
        // Text streamed before the limit stop is kept; the stop itself is not an empty reply.
        assertEquals("calling ", result.text)
    }

    @Test
    fun `blank text and zero tool rounds throws EmptyReply`() = runTest {
        val chat = ScriptedChat()
        chat.script(deltaFlow(CloudStreamEvent.Finished(null)))
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { "" },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "b64" },
        )
        val thrown = runCatching {
            loop.run(listOf(CloudChatMessage("user", "hi")), RecordingListener())
        }.exceptionOrNull()
        assertTrue(thrown is CloudClientException)
        assertTrue((thrown as CloudClientException).error is CloudError.EmptyReply)
    }

    @Test
    fun `blank text after a tool round does not throw`() = runTest {
        val chat = ScriptedChat()
        chat.script(
            deltaFlow(CloudStreamEvent.ToolCalls(singleCallJson), CloudStreamEvent.Finished(null)),
        )
        chat.script(deltaFlow(CloudStreamEvent.Finished(null)))
        val loop = CloudToolLoop(
            chat = { msgs -> chat(msgs) },
            executeTools = { resultsJson(singleCallJson) },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "b64" },
        )
        val result = loop.run(listOf(CloudChatMessage("user", "hi")), RecordingListener())
        assertEquals("", result.text)
        assertEquals(1, result.toolRounds)
    }

    @Test
    fun `cancellation propagates instead of being swallowed`() = runTest {
        val chat: (List<CloudChatMessage>) -> Flow<CloudStreamEvent> = {
            flow {
                emit(CloudStreamEvent.Delta("partial"))
                awaitCancellation()
            }
        }
        val loop = CloudToolLoop(
            chat = chat,
            executeTools = { "" },
            prepareImages = echoPrepare(),
            imageBytesToBase64 = { "b64" },
        )
        var caught: Throwable? = null
        val job = launch {
            try {
                loop.run(listOf(CloudChatMessage("user", "hi")), RecordingListener())
            } catch (t: Throwable) {
                caught = t
            }
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertFalse(caught is CloudClientException)
        assertTrue(caught is CancellationException)
    }

    private fun resultsJson(callsJson: String): String {
        val calls = JSONArray(callsJson)
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            results.put(
                JSONObject()
                    .put("id", id)
                    .put("name", call.getString("name"))
                    .put(
                        "content",
                        if (id == "call_1") """{"exit_code":0}""" else """{"command":"ls"}""",
                    ),
            )
        }
        return results.toString()
    }

    /** Simulates what a no-vision prepare does: sets the tool result's image note. */
    private fun withNoVisionNote(resultsJson: String): String {
        val results = JSONArray(resultsJson)
        for (i in 0 until results.length()) {
            val entry = results.getJSONObject(i)
            val content = JSONObject(entry.getString("content"))
            content.put("image", ToolImageAttachments.NO_VISION_NOTE)
            entry.put("content", content.toString())
        }
        return results.toString()
    }
}
