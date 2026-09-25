package com.druk.lmplayground.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudEndpointsTest {

    @Test
    fun `join strips trailing slash on base`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            CloudEndpoints.join("https://openrouter.ai/api/v1/", "chat/completions"),
        )
    }

    @Test
    fun `join strips leading slash on path`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            CloudEndpoints.join("https://api.openai.com/v1", "/models"),
        )
    }

    @Test
    fun `join rejects empty base`() {
        assertThrows(IllegalArgumentException::class.java) {
            CloudEndpoints.join("  ", "models")
        }
    }

    @Test
    fun `chatCompletionsUrl matches OpenRouter preset root`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            CloudEndpoints.chatCompletionsUrl("https://openrouter.ai/api/v1"),
        )
    }
}
