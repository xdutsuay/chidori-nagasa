package com.druk.lmplayground.coordinator.node

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Minimal OpenAI-compatible facade for node mode (NODE_MODE_SPIKE.md).
 * Serves GET /v1/models and POST /v1/chat/completions (non-streaming).
 * Stdlib ServerSocket — no new dependency.
 */
class NodeOpenAiServer(
    private val bridge: () -> NodeInferenceBridge,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool()

    /** Bind ephemeral port; returns the local listen port. */
    fun start(): Int {
        check(!running.get()) { "already started" }
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(0))
        serverSocket = ss
        running.set(true)
        val workers = pool
        workers.execute {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    workers.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        return ss.localPort
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeResponse(s, 400, "text/plain", "bad request")
                return
            }
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else {
                ""
            }

            when {
                method == "GET" && (path == "/v1/models" || path == "/models") -> {
                    val model = bridge().currentModel()
                    val data = JSONArray()
                    if (model != null) {
                        data.put(
                            JSONObject()
                                .put("id", model.id)
                                .put("object", "model")
                                .put("owned_by", "chidori-nagasa"),
                        )
                    }
                    writeResponse(
                        s,
                        200,
                        "application/json",
                        JSONObject().put("object", "list").put("data", data).toString(),
                    )
                }
                method == "POST" && (
                    path == "/v1/chat/completions" || path == "/chat/completions"
                    ) -> {
                    handleChatCompletions(s, body)
                }
                else -> writeResponse(s, 404, "text/plain", "not found")
            }
        }
    }

    private fun handleChatCompletions(socket: Socket, body: String) {
        val messages = mutableListOf<NodeChatMessage>()
        try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                messages.add(
                    NodeChatMessage(
                        role = m.optString("role", "user"),
                        content = m.optString("content", ""),
                    ),
                )
            }
        } catch (e: Exception) {
            writeResponse(socket, 400, "application/json", errorJson("invalid body: ${e.message}"))
            return
        }
        val modelId = bridge().currentModel()?.id ?: "unknown"
        val result = runBlocking { bridge().complete(messages) }
        result.fold(
            onSuccess = { text ->
                val resp = JSONObject()
                    .put("id", "chatcmpl-${UUID.randomUUID()}")
                    .put("object", "chat.completion")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("model", modelId)
                    .put(
                        "choices",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "message",
                                    JSONObject()
                                        .put("role", "assistant")
                                        .put("content", text),
                                )
                                .put("finish_reason", "stop"),
                        ),
                    )
                    .put(
                        "usage",
                        JSONObject()
                            .put("prompt_tokens", 0)
                            .put("completion_tokens", 0)
                            .put("total_tokens", 0),
                    )
                writeResponse(socket, 200, "application/json", resp.toString())
            },
            onFailure = { e ->
                Log.w(TAG, "node completion failed", e)
                val code = if (e.message?.contains("busy", ignoreCase = true) == true) 503 else 503
                writeResponse(socket, code, "application/json", errorJson(e.message ?: "unavailable"))
            },
        )
    }

    private fun errorJson(message: String): String =
        JSONObject()
            .put("error", JSONObject().put("message", message).put("type", "server_error"))
            .toString()

    private fun writeResponse(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            404 -> "404 Not Found"
            503 -> "503 Service Unavailable"
            else -> "$code"
        }
        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { out ->
            out.write("HTTP/1.1 $status\r\n")
            out.write("Content-Type: $contentType\r\n")
            out.write("Content-Length: ${bytes.size}\r\n")
            out.write("Connection: close\r\n")
            out.write("\r\n")
            out.flush()
        }
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private companion object {
        const val TAG = "NodeOpenAiServer"
    }
}
