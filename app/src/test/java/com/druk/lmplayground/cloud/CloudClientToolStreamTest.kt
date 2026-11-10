package com.druk.lmplayground.cloud

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [CloudClient.streamChat] with tools: the tools JSON travels in the request
 * body and tool-call deltas surface as exactly one [CloudStreamEvent.ToolCalls]
 * before [CloudStreamEvent.Finished].
 */
class CloudClientToolStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: CloudClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = CloudClient(
            http = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base(): String = server.url("/v1").toString().trimEnd('/')

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    @Test
    fun `streamChat sends tools in the request body`() = runTest {
        val tools =
            """[{"type":"function","function":{"name":"cos_gui","description":"d","parameters":{}}}]"""
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        client.streamChat(
            baseUrl = base(),
            apiKey = "sk-test",
            model = "m1",
            messages = listOf(CloudChatMessage("user", "hello")),
            toolsJson = tools,
        ).toList()
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.has("tools"))
        val toolsArr = body.getJSONArray("tools")
        assertEquals(1, toolsArr.length())
        assertEquals(
            "cos_gui",
            toolsArr.getJSONObject(0).getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun `streamChat without toolsJson omits the tools key`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        client.streamChat(
            baseUrl = base(),
            apiKey = "sk-test",
            model = "m1",
            messages = listOf(CloudChatMessage("user", "hello")),
        ).toList()
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
    }

    @Test
    fun `tool call deltas emit deltas then exactly one ToolCalls then Finished`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"let me \"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"cos_gui\",\"arguments\":\"\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"action\\\":\\\"click\\\"}\"}}]}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = client.streamChat(
            baseUrl = base(),
            apiKey = "sk-test",
            model = "m1",
            messages = listOf(CloudChatMessage("user", "open settings")),
        ).toList()

        assertEquals("let me ", (events[0] as CloudStreamEvent.Delta).text)
        val toolCalls = events.filterIsInstance<CloudStreamEvent.ToolCalls>()
        assertEquals("exactly one ToolCalls event", 1, toolCalls.size)
        val arr = JSONArray(toolCalls.single().json)
        assertEquals(1, arr.length())
        assertEquals("call_1", arr.getJSONObject(0).getString("id"))
        assertEquals("cos_gui", arr.getJSONObject(0).getString("name"))
        assertEquals("""{"action":"click"}""", arr.getJSONObject(0).getString("arguments"))
        val finishedIdx = events.indexOfFirst { it is CloudStreamEvent.Finished }
        assertTrue(finishedIdx > events.indexOfFirst { it is CloudStreamEvent.ToolCalls })
    }

    @Test
    fun `text-only stream emits no ToolCalls`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )
        val events = client.streamChat(
            baseUrl = base(),
            apiKey = "sk-test",
            model = "m1",
            messages = listOf(CloudChatMessage("user", "hello")),
        ).toList()
        assertTrue(events.filterIsInstance<CloudStreamEvent.ToolCalls>().isEmpty())
        assertEquals(2, events.filterIsInstance<CloudStreamEvent.Delta>().size)
    }
}
