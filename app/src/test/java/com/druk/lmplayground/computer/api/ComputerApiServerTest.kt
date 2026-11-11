package com.druk.lmplayground.computer.api

import com.druk.lmplayground.tools.FakeDesktopBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Spec tests for [ComputerApiServer]: a real stdlib ServerSocket on loopback with an
 * ephemeral port (port 0 — never the fixed [ComputerApiServer.DEFAULT_PORT], so tests
 * never collide with a dev server). One raw-socket client per request; every server
 * started by a test is stopped in [After] clean-up.
 */
class ComputerApiServerTest {

    private val token = "secret-token"
    private val bridge = FakeDesktopBridge()
    private val routes = ComputerApiRoutes(token) { bridge }
    private val servers = ArrayList<ComputerApiServer>()

    @Before
    fun setUp() {
        servers.clear()
    }

    @After
    fun tearDown() {
        for (s in servers) s.stop()
    }

    private fun server(host: String = ComputerApiServer.LOOPBACK, port: Int = 0): ComputerApiServer =
        ComputerApiServer(routes, host, port).also { servers += it }

    /** One HTTP/1.1 request over a raw socket; reads to EOF (the server must close). */
    private fun request(port: Int, raw: String, timeoutMs: Int = 5_000): RawResponse {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ComputerApiServer.LOOPBACK, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(raw.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val bytes = try {
                socket.getInputStream().readBytes()
            } catch (e: SocketTimeoutException) {
                throw AssertionError(
                    "server did not close the connection within ${timeoutMs}ms " +
                        "(Connection: close / one request per connection)", e,
                )
            }
            return RawResponse.parse(bytes)
        }
    }

    private fun get(port: Int, target: String, authorization: String? = "Bearer $token"): RawResponse {
        val headers = StringBuilder()
            .append("Host: 127.0.0.1\r\n")
        if (authorization != null) headers.append("Authorization: ").append(authorization).append("\r\n")
        return request(
            port,
            "GET $target HTTP/1.1\r\n$headers\r\n",
        )
    }

    private class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        companion object {
            fun parse(bytes: ByteArray): RawResponse {
                val text = String(bytes, Charsets.ISO_8859_1)
                val endOfHeaders = text.indexOf("\r\n\r\n")
                assertTrue("no header/body separator in response: $text", endOfHeaders >= 0)
                val head = text.substring(0, endOfHeaders)
                val lines = head.split("\r\n")
                val statusLine = lines.first()
                assertTrue("not an HTTP status line: $statusLine", statusLine.startsWith("HTTP/1.1 "))
                val headers = lines.drop(1).associate {
                    val idx = it.indexOf(':')
                    assertTrue("malformed header line: $it", idx > 0)
                    it.substring(0, idx).trim().lowercase() to it.substring(idx + 1).trim()
                }
                val bodyStart = endOfHeaders + 4
                return RawResponse(
                    statusLine.substringAfter(' ').substringBefore(' ').toInt(),
                    headers,
                    bytes.copyOfRange(bodyStart, bytes.size),
                )
            }
        }
    }

    // ---- start / binding ----

    @Test
    fun `start with port 0 binds an ephemeral loopback port and reports it running`() {
        val s = server()
        val port = s.start()
        assertTrue("start() must return the bound port (> 0), was $port", port > 0)
        assertTrue(s.isRunning)
    }

    @Test
    fun `host 0_0_0_0 is rejected with IllegalArgumentException before binding`() {
        val s = server(host = "0.0.0.0")
        assertThrows(IllegalArgumentException::class.java) { s.start() }
        assertFalse(s.isRunning)
    }

    @Test
    fun `a LAN host is rejected with IllegalArgumentException before binding`() {
        val s = server(host = "192.168.1.5")
        assertThrows(IllegalArgumentException::class.java) { s.start() }
        assertFalse(s.isRunning)
    }

    @Test
    fun `starting twice throws IllegalStateException`() {
        val s = server()
        s.start()
        assertThrows(IllegalStateException::class.java) { s.start() }
        assertTrue("the first start must stay up", s.isRunning)
    }

    // ---- request handling ----

    @Test
    fun `GET state with a token is 200 with exact Content-Length and no caching`() {
        val port = server().start()
        val r = get(port, ComputerApiRoutes.STATE_PATH)
        assertEquals(200, r.status)
        assertEquals(ComputerApiRoutes.JSON, r.header("Content-Type"))
        assertEquals(
            "Content-Length must be the exact body byte count",
            r.body.size.toString(),
            r.header("Content-Length"),
        )
        assertEquals("no-store", r.header("Cache-Control"))
        assertEquals("close", r.header("Connection"))
        assertTrue(r.body.isNotEmpty())
    }

    @Test
    fun `GET state without a token is 401`() {
        val port = server().start()
        assertEquals(401, get(port, ComputerApiRoutes.STATE_PATH, authorization = null).status)
    }

    @Test
    fun `a garbage request line is 400 text plain`() {
        val port = server().start()
        val r = request(port, "THIS IS NOT A REQUEST LINE\r\n\r\n")
        assertEquals(400, r.status)
        assertTrue(
            "malformed request errors must be text/plain, was ${r.header("Content-Type")}",
            r.header("Content-Type")!!.startsWith("text/plain"),
        )
    }

    // ---- stop ----

    @Test
    fun `stop makes isRunning false and refuses new connections`() {
        val s = server()
        val port = s.start()
        assertEquals(200, get(port, ComputerApiRoutes.STATE_PATH).status)

        s.stop()
        assertFalse(s.isRunning)
        assertThrows(IOException::class.java) {
            Socket().use { it.connect(InetSocketAddress(ComputerApiServer.LOOPBACK, port), 2_000) }
        }
    }

    @Test
    fun `stop is idempotent`() {
        val s = server()
        s.start()
        s.stop()
        s.stop()
        assertFalse(s.isRunning)
    }

    // ---- end-to-end sanity through the real server ----

    @Test
    fun `the server serves the routes' JSON state end to end`() {
        val port = server().start()
        val r = get(port, ComputerApiRoutes.STATE_PATH)
        assertEquals(200, r.status)
        val body = String(r.body, Charsets.UTF_8)
        assertTrue("expected running state JSON, was: $body", body.contains("\"running\""))
        assertTrue(body.contains("1280"))
    }
}
