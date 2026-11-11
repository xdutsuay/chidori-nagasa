package com.xdutsuay.chidori.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CP7: [ToolCallInfoMapper] must surface the cos_exec `exit_code` from the
 * result JSON (`{"exit_code":N,"stdout":...,"stderr":...}`) as
 * [ToolCallInfo.exitCode], while keeping the existing `stopped` behaviour
 * (cancelled:true / "cancelled: ..." error prefix) untouched.
 */
@RunWith(RobolectricTestRunner::class)
class ToolCallInfoMapperExitCodeTest {

    private fun callWithResult(resultContent: String): ToolCallInfo {
        val calls = """[{"id": "1", "name": "cos_exec", "arguments": "{\"command\":\"ls -la\"}"}]"""
        val results = """[{"id": "1", "content": ${org.json.JSONObject.quote(resultContent)}}]"""
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, 100)
        assertEquals(1, infos.size)
        return infos[0]
    }

    @Test
    fun `exit_code is extracted from cos_exec result JSON`() {
        val info = callWithResult("""{"exit_code":42,"stdout":"file.txt","stderr":""}""")
        assertEquals(42, info.exitCode)
    }

    @Test
    fun `exit_code zero is preserved and not collapsed to null`() {
        val info = callWithResult("""{"exit_code":0,"stdout":"ok","stderr":""}""")
        assertEquals(0, info.exitCode)
    }

    @Test
    fun `nonzero failure exit_code is preserved`() {
        val info = callWithResult("""{"exit_code":127,"stdout":"","stderr":"command not found"}""")
        assertEquals(127, info.exitCode)
    }

    @Test
    fun `cancelled cos_exec result keeps stopped true and still reports exit_code 130`() {
        val info = callWithResult(
            """{"exit_code":130,"stdout":"","stderr":"cancelled: the user pressed Stop; the command was terminated before it finished","cancelled":true,"error":"cancelled: the user pressed Stop; the command was terminated before it finished"}""",
        )
        assertTrue(info.stopped)
        assertEquals(130, info.exitCode)
    }

    @Test
    fun `skipped call with cancelled error prefix is stopped with null exit_code`() {
        val info = callWithResult("""{"error":"cancelled: user pressed Stop before this call ran"}""")
        assertTrue(info.stopped)
        assertNull(info.exitCode)
    }

    @Test
    fun `result without exit_code yields null exit_code`() {
        val info = callWithResult("""{"error":"boom"}""")
        assertNull(info.exitCode)
        assertFalse(info.stopped)
    }

    @Test
    fun `plain text result yields null exit_code`() {
        val info = callWithResult("page body")
        assertNull(info.exitCode)
        assertFalse(info.stopped)
    }

    @Test
    fun `missing result yields null exit_code`() {
        val calls = """[{"id": "1", "name": "cos_exec", "arguments": "{}"}]"""
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, "[]", 50)
        assertEquals(1, infos.size)
        assertNull(infos[0].exitCode)
    }

    @Test
    fun `non cos tool result with no exit_code stays null`() {
        val calls = """[{"id": "1", "name": "web_search", "arguments": "{\"query\":\"a\"}"}]"""
        val results = """[{"id": "1", "content": "search hits"}]"""
        val infos = ToolCallInfoMapper.buildToolCallInfoList(calls, results, 100)
        assertEquals(1, infos.size)
        assertNull(infos[0].exitCode)
    }
}
