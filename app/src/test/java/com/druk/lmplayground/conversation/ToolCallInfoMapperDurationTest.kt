package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Per-call durations on the assistant message's tool rows: when a result entry
 * carries `duration_ms` (written by [com.druk.lmplayground.tools.ToolRegistry]
 * at execution time), the mapper must use it verbatim; entries without the key
 * (or with no entry at all) keep the old even-split fallback
 * `totalDurationMs / calls.length`. Mixed batches mix both.
 */
@RunWith(RobolectricTestRunner::class)
class ToolCallInfoMapperDurationTest {

    private fun callsJson(vararg ids: String): String {
        val arr = org.json.JSONArray()
        ids.forEachIndexed { i, id ->
            arr.put(
                org.json.JSONObject()
                    .put("id", id)
                    .put("name", "tool_$i")
                    .put("arguments", "{}"),
            )
        }
        return arr.toString()
    }

    private fun resultJson(id: String, durationMs: Long?): String {
        val obj = org.json.JSONObject().put("id", id).put("content", "result of $id")
        if (durationMs != null) obj.put("duration_ms", durationMs)
        return obj.toString()
    }

    private fun resultsJson(vararg results: String): String =
        org.json.JSONArray().apply { results.forEach { put(org.json.JSONObject(it)) } }.toString()

    @Test
    fun `per-entry duration_ms wins over the even split`() {
        val calls = callsJson("1", "2")
        val results = resultsJson(resultJson("1", 5), resultJson("2", 4000))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, totalDurationMs = 4005)
        assertEquals(5L, infos[0].durationMs)
        assertEquals(4000L, infos[1].durationMs)
    }

    @Test
    fun `entries without duration_ms keep the even-split fallback`() {
        val calls = callsJson("1", "2")
        val results = resultsJson(resultJson("1", null), resultJson("2", null))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, totalDurationMs = 1001)
        assertEquals(500L, infos[0].durationMs)
        assertEquals(500L, infos[1].durationMs)
    }

    @Test
    fun `mixed batch mixes per-entry and fallback durations`() {
        val calls = callsJson("1", "2")
        val results = resultsJson(resultJson("1", 5), resultJson("2", null))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, totalDurationMs = 1000)
        assertEquals(5L, infos[0].durationMs)
        assertEquals(500L, infos[1].durationMs)
    }

    @Test
    fun `call with no result entry at all uses the fallback`() {
        val calls = callsJson("1", "2")
        val results = resultsJson(resultJson("1", 7))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, totalDurationMs = 1000)
        assertEquals(7L, infos[0].durationMs)
        assertEquals(500L, infos[1].durationMs)
    }

    @Test
    fun `single call with duration_ms uses it instead of the whole round`() {
        val calls = callsJson("1")
        val results = resultsJson(resultJson("1", 5))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, totalDurationMs = 4005)
        assertEquals(5L, infos[0].durationMs)
    }

    @Test
    fun `duration_ms does not disturb stopped and exit_code parsing`() {
        val calls = """[{"id": "1", "name": "cos_exec", "arguments": "{}"}]"""
        val content = """{"exit_code":130,"stdout":"","stderr":"cancelled: user pressed Stop","cancelled":true,"error":"cancelled: user pressed Stop"}"""
        val results = org.json.JSONArray()
            .put(
                org.json.JSONObject()
                    .put("id", "1")
                    .put("content", content)
                    .put("duration_ms", 42),
            )
            .toString()
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, 42)
        assertEquals(1, infos.size)
        assertEquals(42L, infos[0].durationMs)
        assertTrue(infos[0].stopped)
        assertEquals(130, infos[0].exitCode)
    }

    @Test
    fun `zero duration_ms is honored and not treated as missing`() {
        val calls = callsJson("1")
        val results = resultsJson(resultJson("1", 0))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, totalDurationMs = 999)
        assertEquals(0L, infos[0].durationMs)
    }

    @Test
    fun `results untouched when entries have duration_ms`() {
        val calls = callsJson("1")
        val results = resultsJson(resultJson("1", 5))
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, 5)
        assertEquals("result of 1", infos[0].result)
        assertNull(infos[0].exitCode)
    }
}
