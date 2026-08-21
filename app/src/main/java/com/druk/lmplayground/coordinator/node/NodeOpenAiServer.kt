package com.druk.lmplayground.coordinator.node

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private var pool = newWorkerPool()

    /** Bind ephemeral port; returns the local listen port. */
    fun start(): Int {
        check(!running.get()) { "already started" }
        if (pool.isShutdown) pool = newWorkerPool()
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
                    workers.execute {
                        try {
                            handleClient(client)
                        } catch (t: Throwable) {
                            Log.e(TAG, "client handler failed", t)
                        }
                    }
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
            s.soTimeout = HEADER_TIMEOUT_MS
            val req = readHttp(s) ?: return
            if (req.errorStatus != 0) {
                writeResponse(s, req.errorStatus, "text/plain", req.errorBody)
                return
            }
            // Generation on-device can exceed any header timeout; disable SO_TIMEOUT
            // for the rest of the connection (KMA-64 / Agent EOF).
            s.soTimeout = 0
            val method = req.method
            val path = req.path
            val body = req.body

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
            val tools = json.optJSONArray("tools")
            if (tools != null && tools.length() > 0) {
                writeResponse(
                    socket,
                    400,
                    "application/json",
                    errorJson("this phone node is Ask-only; Agent/tools are not supported"),
                )
                return
            }
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
        // Do not wrap getOutputStream() in a Writer.use {} — closing the writer
        // closes the socket, so the body write hit "Socket is closed" and the
        // desktop decoder saw unexpected EOF (KMA-64 framing).
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        val out = socket.getOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    /** Byte-accurate HTTP/1.1 request: headers then Content-Length body (KMA-64). */
    private fun readHttp(socket: Socket): ParsedHttp? {
        val ins = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var match = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val b = ins.read()
            if (b < 0) {
                return if (headerBytes.size() == 0) {
                    null
                } else {
                    ParsedHttp(errorStatus = 400, errorBody = "incomplete headers")
                }
            }
            headerBytes.write(b)
            if (b == CRLF4[match].toInt()) {
                match++
                if (match == 4) break
            } else {
                match = if (b == CRLF4[0].toInt()) 1 else 0
            }
        }
        if (match != 4) {
            return ParsedHttp(errorStatus = 400, errorBody = "headers too large")
        }
        val headerText = headerBytes.toString(Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            return ParsedHttp(errorStatus = 400, errorBody = "bad request")
        }
        var contentLength = 0
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val lower = line.lowercase()
            if (lower.startsWith("content-length:")) {
                contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) {
            return ParsedHttp(errorStatus = 400, errorBody = "invalid content-length")
        }
        if (contentLength > MAX_BODY_BYTES) {
            return ParsedHttp(errorStatus = 400, errorBody = "payload too large")
        }
        val bodyBytes = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            val n = ins.read(bodyBytes, off, contentLength - off)
            if (n < 0) {
                return ParsedHttp(errorStatus = 400, errorBody = "incomplete body")
            }
            off += n
        }
        return ParsedHttp(
            method = parts[0],
            path = parts[1].substringBefore('?'),
            body = String(bodyBytes, Charsets.UTF_8),
        )
    }

    private data class ParsedHttp(
        val method: String = "",
        val path: String = "",
        val body: String = "",
        val errorStatus: Int = 0,
        val errorBody: String = "",
    )

    private companion object {
        const val TAG = "NodeOpenAiServer"
        const val HEADER_TIMEOUT_MS = 30_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 1 * 1024 * 1024
        val CRLF4 = byteArrayOf(13, 10, 13, 10)

        // Accept loop + a small number of in-flight completions (KMA-65 bound).
        fun newWorkerPool() = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "node-openai").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                    Log.e(TAG, "uncaught in ${t.name}", e)
                }
            }
        }
    }
}
