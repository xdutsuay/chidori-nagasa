package com.xdutsuay.chidori.cloud

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire shape of [CloudPayload]: assistant tool calls, tool results, user image
 * parts, and the tools/stream request envelope (see the CINDI-agent handout).
 */
class CloudPayloadTest {

    private val callsJson =
        """[{"id":"call_1","name":"cos_gui","arguments":"{\"action\":\"click\",\"x\":1}"}]"""

    // ---- messagesJson ----

    @Test
    fun `plain messages keep role and string content`() {
        val arr = CloudPayload.messagesJson(
            listOf(
                CloudChatMessage("system", "You are CINDI."),
                CloudChatMessage("user", "hello"),
            ),
        )
        assertEquals(2, arr.length())
        assertEquals("system", arr.getJSONObject(0).getString("role"))
        assertEquals("You are CINDI.", arr.getJSONObject(0).getString("content"))
        assertEquals("user", arr.getJSONObject(1).getString("role"))
        assertEquals("hello", arr.getJSONObject(1).getString("content"))
    }

    @Test
    fun `assistant with tool calls sends type function blocks and keeps content`() {
        val m = CloudPayload.messagesJson(
            listOf(CloudChatMessage("assistant", "opening the app", toolCallsJson = callsJson)),
        ).getJSONObject(0)
        assertEquals("assistant", m.getString("role"))
        assertEquals("opening the app", m.getString("content"))
        val calls = m.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        val call = calls.getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        val fn = call.getJSONObject("function")
        assertEquals("cos_gui", fn.getString("name"))
        assertEquals("""{"action":"click","x":1}""", fn.getString("arguments"))
    }

    @Test
    fun `assistant with tool calls and blank content sends null content`() {
        val m = CloudPayload.messagesJson(
            listOf(CloudChatMessage("assistant", "", toolCallsJson = callsJson)),
        ).getJSONObject(0)
        assertTrue(m.isNull("content"))
        assertEquals(1, m.getJSONArray("tool_calls").length())
    }

    @Test
    fun `assistant without tool calls has no tool_calls key`() {
        val m = CloudPayload.messagesJson(
            listOf(CloudChatMessage("assistant", "plain answer")),
        ).getJSONObject(0)
        assertFalse(m.has("tool_calls"))
        assertEquals("plain answer", m.getString("content"))
    }

    @Test
    fun `tool message sends tool_call_id and content`() {
        val m = CloudPayload.messagesJson(
            listOf(CloudChatMessage("tool", """{"exit_code":0}""", toolCallId = "call_1")),
        ).getJSONObject(0)
        assertEquals("tool", m.getString("role"))
        assertEquals("call_1", m.getString("tool_call_id"))
        assertEquals("""{"exit_code":0}""", m.getString("content"))
    }

    @Test
    fun `user with image sends text plus image_url data uri`() {
        val m = CloudPayload.messagesJson(
            listOf(CloudChatMessage("user", "here is the screen", imagePngBase64 = "QUJD")),
        ).getJSONObject(0)
        assertEquals("user", m.getString("role"))
        val parts = m.getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("here is the screen", parts.getJSONObject(0).getString("text"))
        val image = parts.getJSONObject(1)
        assertEquals("image_url", image.getString("type"))
        assertEquals(
            "data:image/png;base64,QUJD",
            image.getJSONObject("image_url").getString("url"),
        )
    }

    // ---- requestJson ----

    private fun messages() = listOf(CloudChatMessage("user", "hi"))

    @Test
    fun `request sets model stream and messages`() {
        val root = CloudPayload.requestJson("openrouter/gpt", messages(), null)
        assertEquals("openrouter/gpt", root.getString("model"))
        assertTrue(root.getBoolean("stream"))
        assertEquals(1, root.getJSONArray("messages").length())
    }

    @Test
    fun `tools key omitted for null blank and empty array`() {
        for (tools in listOf<String?>(null, "", "   ", "[]")) {
            assertFalse(
                "tools present for toolsJson=$tools",
                CloudPayload.requestJson("m", messages(), tools).has("tools"),
            )
        }
    }

    @Test
    fun `tools key carries the tools JSON array when present`() {
        val tools = """[{"type":"function","function":{"name":"cos_gui","description":"d","parameters":{}}}]"""
        val root = CloudPayload.requestJson("m", messages(), tools)
        assertTrue(root.has("tools"))
        val arr = root.getJSONArray("tools")
        assertEquals(1, arr.length())
        assertEquals(
            "cos_gui",
            arr.getJSONObject(0).getJSONObject("function").getString("name"),
        )
    }
}
