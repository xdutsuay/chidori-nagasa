package com.xdutsuay.chidori.cloud

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming tool-call fragments (OpenAI `choices[0].delta.tool_calls`) parsed by
 * [CloudSseParser.toolCallDeltasFromDataLine] and merged into the
 * `[{id,name,arguments}]` JSON that `ToolRegistry.executeToolCalls` reads.
 */
class CloudToolCallsTest {

    // ---- CloudSseParser.toolCallDeltasFromDataLine ----

    @Test
    fun `first tool fragment carries index id and function name`() {
        val line =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"cos_gui","arguments":""}}]}}]}"""
        val deltas = CloudSseParser.toolCallDeltasFromDataLine(line)
        assertEquals(1, deltas.size)
        assertEquals(0, deltas[0].index)
        assertEquals("call_1", deltas[0].id)
        assertEquals("cos_gui", deltas[0].name)
    }

    @Test
    fun `later tool fragments carry only index and arguments`() {
        val line =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"act"}}]}}]}"""
        val deltas = CloudSseParser.toolCallDeltasFromDataLine(line)
        assertEquals(1, deltas.size)
        assertEquals(0, deltas[0].index)
        assertNull(deltas[0].id)
        assertNull(deltas[0].name)
        assertEquals("{\"act", deltas[0].argumentsChunk)
    }

    @Test
    fun `second parallel tool fragment uses its own index`() {
        val line =
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","type":"function","function":{"name":"cos_exec","arguments":""}}]}}]}"""
        val deltas = CloudSseParser.toolCallDeltasFromDataLine(line)
        assertEquals(1, deltas.size)
        assertEquals(1, deltas[0].index)
        assertEquals("call_2", deltas[0].id)
        assertEquals("cos_exec", deltas[0].name)
    }

    @Test
    fun `text-only chunk yields no tool call deltas`() {
        assertTrue(
            CloudSseParser.toolCallDeltasFromDataLine(
                """{"choices":[{"delta":{"content":"Hello"}}]}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `role-only chunk yields no tool call deltas`() {
        assertTrue(
            CloudSseParser.toolCallDeltasFromDataLine(
                """{"choices":[{"delta":{"role":"assistant"}}]}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `done marker yields no tool call deltas`() {
        assertTrue(CloudSseParser.toolCallDeltasFromDataLine("[DONE]").isEmpty())
    }

    @Test
    fun `junk yields no tool call deltas`() {
        assertTrue(CloudSseParser.toolCallDeltasFromDataLine("not json at all").isEmpty())
        assertTrue(CloudSseParser.toolCallDeltasFromDataLine("").isEmpty())
        assertTrue(CloudSseParser.toolCallDeltasFromDataLine("""{"choices":"broken"}""").isEmpty())
    }

    // ---- CloudToolCallAccumulator ----

    @Test
    fun `accumulator is empty before any fragment`() {
        assertTrue(CloudToolCallAccumulator().isEmpty())
    }

    @Test
    fun `accumulator merges fragments by index into full arguments`() {
        val acc = CloudToolCallAccumulator()
        acc.add(CloudToolCallDelta(0, "call_1", "cos_gui", ""))
        acc.add(CloudToolCallDelta(0, null, null, "{\"action\":"))
        acc.add(CloudToolCallDelta(0, null, null, "\"click\",\"x\":10,\"y\":20}"))
        assertFalse(acc.isEmpty())
        val arr = JSONArray(acc.toJson())
        assertEquals(1, arr.length())
        val call = arr.getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("cos_gui", call.getString("name"))
        assertEquals("""{"action":"click","x":10,"y":20}""", call.getString("arguments"))
    }

    @Test
    fun `accumulator orders two parallel calls by index`() {
        val acc = CloudToolCallAccumulator()
        acc.add(CloudToolCallDelta(0, "call_a", "cos_gui", "{\"action\":"))
        acc.add(CloudToolCallDelta(1, "call_b", "cos_exec", "{\"command\":"))
        acc.add(CloudToolCallDelta(0, null, null, "\"key\",\"keys\":\"Return\"}"))
        acc.add(CloudToolCallDelta(1, null, null, "\"ls\"}"))
        val arr = JSONArray(acc.toJson())
        assertEquals(2, arr.length())
        assertEquals("call_a", arr.getJSONObject(0).getString("id"))
        assertEquals("cos_gui", arr.getJSONObject(0).getString("name"))
        assertEquals("""{"action":"key","keys":"Return"}""", arr.getJSONObject(0).getString("arguments"))
        assertEquals("call_b", arr.getJSONObject(1).getString("id"))
        assertEquals("cos_exec", arr.getJSONObject(1).getString("name"))
        assertEquals("""{"command":"ls"}""", arr.getJSONObject(1).getString("arguments"))
    }

    @Test
    fun `accumulator defaults missing id to call_index`() {
        val acc = CloudToolCallAccumulator()
        acc.add(CloudToolCallDelta(2, null, "cos_gui", "{\"action\":\"move\"}"))
        val arr = JSONArray(acc.toJson())
        assertEquals(1, arr.length())
        assertEquals("call_2", arr.getJSONObject(0).getString("id"))
        assertEquals("cos_gui", arr.getJSONObject(0).getString("name"))
    }

    @Test
    fun `accumulator defaults missing arguments to empty object`() {
        val acc = CloudToolCallAccumulator()
        acc.add(CloudToolCallDelta(0, "call_x", "cos_gui", null))
        val arr = JSONArray(acc.toJson())
        assertEquals(1, arr.length())
        assertEquals("call_x", arr.getJSONObject(0).getString("id"))
        assertEquals("{}", arr.getJSONObject(0).getString("arguments"))
    }

    @Test
    fun `toJson is a parseable JSONArray of id name arguments objects`() {
        val acc = CloudToolCallAccumulator()
        acc.add(CloudToolCallDelta(0, "call_1", "cos_gui", "{}"))
        acc.add(CloudToolCallDelta(1, "call_2", "cos_exec", "{}"))
        val arr = JSONArray(acc.toJson())
        assertEquals(2, arr.length())
        for (i in 0 until arr.length()) {
            val call = arr.getJSONObject(i)
            assertTrue(call.has("id"))
            assertTrue(call.has("name"))
            assertTrue(call.has("arguments"))
        }
    }
}
