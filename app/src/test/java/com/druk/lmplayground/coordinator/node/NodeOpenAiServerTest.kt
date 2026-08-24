package com.druk.lmplayground.coordinator.node

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeOpenAiServerTest {
    private val dataPlaneToken = "kma65-test-token"

    private val server = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 4) {
        AbsentNodeInferenceBridge
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `start accepts clients and stop allows restart`() {
        val firstPort = server.start()
        assertTrue(firstPort > 0)

        repeat(4) {
            Socket(InetAddress.getLoopbackAddress(), firstPort).use { socket ->
                assertTrue(socket.isConnected)
            }
        }

        server.stop()

        val secondPort = server.start()
        assertTrue(secondPort > 0)
        Socket(InetAddress.getLoopbackAddress(), secondPort).use { socket ->
            assertTrue(socket.isConnected)
        }
    }

    @Test
    fun `models response includes json body on the same connection`() {
        val srv = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 4) {
            object : NodeInferenceBridge {
                override fun currentModel() = NodeOfferedModel("qwen-test", "Qwen")
                override suspend fun complete(messages: List<NodeChatMessage>) =
                    Result.success("unused")
            }
        }
        val port = srv.start()
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write(
                    (
                        "GET /v1/models HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            bearerHeader() +
                            "Connection: close\r\n" +
                            "\r\n"
                        )
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                val resp = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(resp.contains("200 OK"))
                assertTrue(resp.contains("qwen-test"))
            }
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `unicode chat body is received intact`() {
        val captured = mutableListOf<NodeChatMessage>()
        val srv = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 4) {
            object : NodeInferenceBridge {
                override fun currentModel() = NodeOfferedModel("qwen-test", "Qwen")
                override suspend fun complete(messages: List<NodeChatMessage>): Result<String> {
                    captured.addAll(messages)
                    return Result.success("ok-你好")
                }
            }
        }
        val port = srv.start()
        try {
            val json = """{"messages":[{"role":"user","content":"hello 你好"}]}"""
            val body = json.toByteArray(Charsets.UTF_8)
            val req = (
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    bearerHeader() +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII) + body
            val resp = exchange(port, req)
            assertTrue(resp.contains("200 OK"))
            val payload = resp.substringAfter("\r\n\r\n")
            val content = JSONObject(payload)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            assertEquals("ok-你好", content)
            assertTrue(captured.single().content.contains("你好"))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `oversized body is rejected without unbounded allocation`() {
        val port = server.start()
        val req = (
            "POST /v1/chat/completions HTTP/1.1\r\n" +
                bearerHeader() +
                "Content-Length: 2000000\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val resp = exchange(port, req)
        assertTrue(resp.contains("400"))
        assertTrue(resp.contains("payload too large"))
    }

    @Test
    fun `tools payload is rejected as ask-only`() {
        val srv = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 4) {
            object : NodeInferenceBridge {
                override fun currentModel() = NodeOfferedModel("qwen-test", "Qwen")
                override suspend fun complete(messages: List<NodeChatMessage>) =
                    Result.success("should-not-run")
            }
        }
        val port = srv.start()
        try {
            val json =
                """{"messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function"}]}"""
            val body = json.toByteArray(Charsets.UTF_8)
            val req = (
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    bearerHeader() +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII) + body
            val resp = exchange(port, req)
            assertTrue(resp.contains("400"))
            assertTrue(resp.contains("Ask-only"))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `models requires bearer and does not leak model ids`() {
        val srv = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 4) {
            object : NodeInferenceBridge {
                override fun currentModel() = NodeOfferedModel("qwen-secret", "Qwen")
                override suspend fun complete(messages: List<NodeChatMessage>) =
                    Result.success("unused")
            }
        }
        val unauthorizedHeaders = listOf("", bearerHeader("wrong-token"))
        val requests = unauthorizedHeaders.map { auth ->
            (
                "GET /v1/models HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    auth +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII)
        }

        val port = srv.start()
        try {
            requests.forEach { req ->
                val resp = exchange(port, req)
                assertTrue(resp.contains("401"))
                assertTrue(resp.contains("application/json"))
                assertTrue(responseBody(resp).contains("error"))
                assertTrue(!resp.contains("qwen-secret"))
            }
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `chat completions requires bearer and does not run inference`() {
        val calls = AtomicInteger(0)
        val srv = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 4) {
            object : NodeInferenceBridge {
                override fun currentModel() = NodeOfferedModel("qwen-test", "Qwen")
                override suspend fun complete(messages: List<NodeChatMessage>): Result<String> {
                    calls.incrementAndGet()
                    return Result.success("secret-completion")
                }
            }
        }
        val body = """{"messages":[{"role":"user","content":"hello"}]}""".toByteArray(Charsets.UTF_8)
        val requests = listOf("", bearerHeader("wrong-token")).map { auth ->
            (
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    auth +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII) + body
        }

        val port = srv.start()
        try {
            requests.forEach { req ->
                val resp = exchange(port, req)
                assertTrue(resp.contains("401"))
                assertTrue(resp.contains("application/json"))
                assertTrue(responseBody(resp).contains("error"))
                assertTrue(!resp.contains("secret-completion"))
            }
            assertEquals(0, calls.get())
        } finally {
            srv.stop()
        }
    }

    @Test
    fun `second overlapping completion is rejected when capacity is full`() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completeCalls = AtomicInteger(0)
        val secondCallRan = AtomicBoolean(false)
        val srv = NodeOpenAiServer(dataPlaneToken, maxConcurrentCompletions = 1) {
            object : NodeInferenceBridge {
                override fun currentModel() = NodeOfferedModel("qwen-test", "Qwen")

                override suspend fun complete(messages: List<NodeChatMessage>): Result<String> {
                    return when (completeCalls.incrementAndGet()) {
                        1 -> {
                            firstStarted.countDown()
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                            Result.success("first-ok")
                        }

                        else -> {
                            secondCallRan.set(true)
                            Result.success("second-should-not-run")
                        }
                    }
                }
            }
        }
        val port = srv.start()
        try {
            val firstResponse = AtomicReference<String>()
            val firstThread = Thread {
                firstResponse.set(exchange(port, chatRequest("first")))
            }
            firstThread.start()

            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

            val secondResp = exchange(port, chatRequest("second"))
            assertTrue(secondResp.contains("429") || secondResp.contains("503"))
            assertTrue(responseBody(secondResp).lowercase().contains("capacity") || responseBody(secondResp).lowercase().contains("busy"))
            assertEquals(1, completeCalls.get())
            assertTrue(!secondCallRan.get())

            releaseFirst.countDown()
            firstThread.join(5_000)
            assertTrue(firstResponse.get().contains("200 OK"))
        } finally {
            releaseFirst.countDown()
            srv.stop()
        }
    }

    private fun exchange(port: Int, request: ByteArray): String {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun bearerHeader(token: String = dataPlaneToken): String = "Authorization: Bearer $token\r\n"

    private fun chatRequest(content: String, token: String = dataPlaneToken): ByteArray {
        val body = """{"messages":[{"role":"user","content":"$content"}]}""".toByteArray(Charsets.UTF_8)
        return (
            "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                bearerHeader(token) +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII) + body
    }

    private fun responseBody(response: String): String = response.substringAfter("\r\n\r\n", "")
}
