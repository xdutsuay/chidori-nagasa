package com.druk.lmplayground.coordinator.node

import java.net.InetAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeOpenAiServerTest {

    private val server = NodeOpenAiServer { AbsentNodeInferenceBridge }

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
        val srv = NodeOpenAiServer {
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
                    "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
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
        val srv = NodeOpenAiServer {
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
        val srv = NodeOpenAiServer {
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

    private fun exchange(port: Int, request: ByteArray): String {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
}
