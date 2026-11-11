package com.xdutsuay.chidori.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolCallInfoCodecTest {

    private val sample = listOf(
        ToolCallInfo(
            name = "web_search",
            arguments = "{\"query\":\"weather tokyo\"}",
            result = "{\"hits\":3}",
            durationMs = 1234L,
            precedingThinking = "I should search first",
            precedingThinkingDurationSeconds = 2,
            precedingThinkingTokens = 15,
            stopped = false,
        ),
        ToolCallInfo(
            name = "cos_exec",
            arguments = "{\"cmd\":\"ls -la\"}",
            result = "{\"cancelled\":true}",
            durationMs = 456L,
            stopped = true,
        ),
    )

    @Test
    fun roundTripsAllFields() {
        val decoded = ToolCallInfoCodec.decode(ToolCallInfoCodec.encode(sample)!!)
        assertEquals(sample, decoded)
    }

    @Test
    fun encodesNullAndEmptyToNull() {
        assertNull(ToolCallInfoCodec.encode(null))
        assertNull(ToolCallInfoCodec.encode(emptyList()))
    }

    @Test
    fun decodesNullBlankAndEmptyArrayToNull() {
        assertNull(ToolCallInfoCodec.decode(null))
        assertNull(ToolCallInfoCodec.decode(""))
        assertNull(ToolCallInfoCodec.decode("[]"))
    }

    @Test
    fun decodesMalformedJsonToNull() {
        assertNull(ToolCallInfoCodec.decode("not json at all"))
    }

    @Test
    fun encodedJsonCarriesStoppedAndThinkingFields() {
        val json = ToolCallInfoCodec.encode(sample)!!
        val arr = org.json.JSONArray(json)
        assertEquals(2, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("web_search", first.getString("name"))
        assertTrue(first.has("result"))
        assertTrue(first.has("durationMs"))
        assertEquals("I should search first", first.getString("precedingThinking"))
        assertEquals(15, first.getInt("precedingThinkingTokens"))
        assertEquals(2, first.getInt("precedingThinkingDurationSeconds"))
        assertEquals(false, first.getBoolean("stopped"))
        assertEquals(true, arr.getJSONObject(1).getBoolean("stopped"))
    }
}
