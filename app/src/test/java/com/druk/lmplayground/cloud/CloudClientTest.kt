package com.druk.lmplayground.cloud

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudClientTest {

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

    @Test
    fun `listModels parses data array`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"m2"},{"id":"m1","name":"One"}]}""",
            ),
        )
        val result = client.listModels(base(), "sk-test")
        assertTrue(result.isSuccess)
        assertEquals(listOf("m1", "m2"), result.getOrThrow().map { it.id })
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals("/v1/models", recorded.path)
    }

    @Test
    fun `listModels maps 401 without clearing caller state`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad"}"""))
        val result = client.listModels(base(), "bad")
        assertTrue(result.isFailure)
        val err = (result.exceptionOrNull() as CloudClientException).error
        assertTrue(err is CloudError.Unauthorized)
    }

    @Test
    fun `streamChat emits deltas then finished`() = runTest {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        val events = client.streamChat(
            baseUrl = base(),
            apiKey = "sk-test",
            model = "m1",
            messages = listOf(CloudChatMessage("user", "hello")),
        ).toList()
        assertEquals("Hi", (events[0] as CloudStreamEvent.Delta).text)
        assertEquals("!", (events[1] as CloudStreamEvent.Delta).text)
        val finished = events[2] as CloudStreamEvent.Finished
        assertEquals(1, finished.usage?.promptTokens)
        assertEquals(2, finished.usage?.completionTokens)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"stream\":true"))
    }
}
