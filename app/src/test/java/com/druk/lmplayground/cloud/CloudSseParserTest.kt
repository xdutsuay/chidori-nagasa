package com.druk.lmplayground.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSseParserTest {

    @Test
    fun `delta extracts content from chunk`() {
        val line = """{"choices":[{"delta":{"content":"Hello"}}]}"""
        assertEquals("Hello", CloudSseParser.deltaFromDataLine(line))
    }

    @Test
    fun `done returns null`() {
        assertNull(CloudSseParser.deltaFromDataLine("[DONE]"))
    }

    @Test
    fun `usage reads tokens and cost`() {
        val line = """{"usage":{"prompt_tokens":10,"completion_tokens":5,"cost":0.0012}}"""
        val usage = CloudSseParser.usageFromDataLine(line)!!
        assertEquals(10, usage.promptTokens)
        assertEquals(5, usage.completionTokens)
        assertEquals(0.0012, usage.costUsd!!, 1e-9)
    }

    @Test
    fun `http codes map to typed errors`() {
        assertTrue(CloudSseParser.mapHttpCode(401, "") is CloudError.Unauthorized)
        assertTrue(CloudSseParser.mapHttpCode(402, "") is CloudError.PaymentRequired)
        assertTrue(CloudSseParser.mapHttpCode(429, "") is CloudError.RateLimited)
        val other = CloudSseParser.mapHttpCode(500, "boom") as CloudError.Http
        assertEquals(500, other.code)
    }
}
