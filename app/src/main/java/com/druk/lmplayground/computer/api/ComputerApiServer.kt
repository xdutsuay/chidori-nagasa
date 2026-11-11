package com.druk.lmplayground.computer.api

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loopback-only HTTP/1.1 server for [ComputerApiRoutes] (slice 1, reached with `adb forward`).
 * Stdlib ServerSocket, no new dependency.
 *
 * - [start] binds [host]:[port] (port 0 = ephemeral) and returns the bound port. [host] must be
 *   `127.0.0.1` or `::1`, anything else → [IllegalArgumentException] before binding. Starting
 *   twice → [IllegalStateException].
 * - Reads the request line and headers (at most [MAX_HEADER_BYTES], [HEADER_TIMEOUT_MS]).
 *   Malformed or oversized headers → 400 `text/plain`.
 * - Body (slice 2): exactly `Content-Length` bytes as UTF-8 into [ApiRequest.body]; no header
 *   means an empty body. A non-numeric or negative length → 400; more than [MAX_BODY_BYTES]
 *   → 413 `text/plain` without reading it; fewer bytes than declared before EOF/timeout → 400.
 * - Every response carries `Content-Type`, exact `Content-Length`, `Cache-Control: no-store`
 *   and `Connection: close`; one request per connection.
 * - [stop] closes the socket; idempotent; [isRunning] is false afterwards and new connections
 *   are refused.
 */
class ComputerApiServer(
    private val routes: ComputerApiRoutes,
    private val host: String = LOOPBACK,
    private val port: Int = DEFAULT_PORT,
) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning: Boolean get() = socket?.isClosed == false

    @Synchronized
    fun start(): Int {
        require(host == LOOPBACK || host == LOOPBACK_V6) { "Computer API is loopback-only; refusing $host" }
        check(socket == null) { "already started" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(InetAddress.getByName(host), port))
        } catch (e: Exception) {
            ss.close()
            throw e
        }
        val workers = Executors.newFixedThreadPool(WORKERS) { r ->
            Thread(r, "computer-api").apply { isDaemon = true }
        }
        socket = ss
        pool = workers
        workers.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                try {
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    runCatching { client.close() }
                }
            }
        }
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = HEADER_TIMEOUT_MS
                val resp = when (val r = read(s)) {
                    is Read.Ok -> runBlocking { routes.handle(r.request) }
                    is Read.Fail -> ApiResponse(r.status, TEXT, r.message.toByteArray(Charsets.UTF_8))
                }
                write(s, resp)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request failed", t)
        }
    }

    private sealed interface Read {
        data class Ok(val request: ApiRequest) : Read
        data class Fail(val status: Int, val message: String) : Read
    }

    private fun read(s: Socket): Read {
        val bad = Read.Fail(400, "bad request")
        val ins = s.getInputStream()
        val buf = ByteArrayOutputStream()
        var match = 0
        while (match < 4) {
            if (buf.size() >= MAX_HEADER_BYTES) return bad
            val b = ins.read()
            if (b < 0) return bad
            buf.write(b)
            match = if (b == CRLF4[match].toInt()) match + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = buf.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.") || !parts[1].startsWith("/")) return bad
        fun header(name: String) = lines.drop(1).firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val length = header("content-length")?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return bad } ?: 0
        if (length > MAX_BODY_BYTES) return Read.Fail(413, "payload too large")
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = try { ins.read(body, off, length - off) } catch (_: java.io.IOException) { -1 }
            if (n < 0) return bad
            off += n
        }
        return Read.Ok(ApiRequest(parts[0], parts[1], header("authorization"), String(body, Charsets.UTF_8)))
    }

    private fun write(s: Socket, r: ApiResponse) {
        val head = "HTTP/1.1 ${r.status} ${reason(r.status)}\r\n" +
            "Content-Type: ${r.contentType}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(r.body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        403 -> "Forbidden"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val LOOPBACK_V6 = "::1"
        const val DEFAULT_PORT = 7901
        const val HEADER_TIMEOUT_MS = 10_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 64 * 1024
        private const val WORKERS = 4
        private const val TEXT = "text/plain"
        private const val TAG = "ComputerApiServer"
        private val CRLF4 = byteArrayOf(13, 10, 13, 10)
    }
}
