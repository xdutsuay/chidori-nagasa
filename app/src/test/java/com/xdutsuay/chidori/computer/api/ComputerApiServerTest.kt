package com.xdutsuay.chidori.computer.api

import com.xdutsuay.chidori.tools.FakeDesktopBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
@RunWith(RobolectricTestRunner::class)
class ComputerApiServerTest {

    private val token = "secret-token"
    private val bridge = FakeDesktopBridge()
    private val control = FakeComputerControl()
    private val routes = ComputerApiRoutes(token) { bridge }
    private val controlRoutes = ComputerApiRoutes(token, control) { bridge }
    private val servers = ArrayList<ComputerApiServer>()

    @Before
    fun setUp() {
        servers.clear()
    }

    @After
    fun tearDown() {
        for (s in servers) s.stop()
    }

    private fun server(
        routes: ComputerApiRoutes = this.routes,
        host: String = ComputerApiServer.LOOPBACK,
        port: Int = 0,
        sslContext: javax.net.ssl.SSLContext? = null,
        onResponse: (Int) -> Unit = {},
    ): ComputerApiServer =
        ComputerApiServer(routes, host, port, sslContext, onResponse).also { servers += it }

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

    /**
     * POSTs [body] with a `Content-Length` header (default: the exact body size).
     * [sendBytes] limits how many body bytes are actually written (for the short-body and
     * oversized cases); [shutdownBeforeRead] closes the write side before reading (EOF case).
     */
    private fun post(
        port: Int,
        target: String,
        body: String = "",
        contentLength: String? = body.length.toString(),
        sendBytes: Int = body.length,
        shutdownBeforeRead: Boolean = false,
        authorization: String? = "Bearer $token",
        timeoutMs: Int = 5_000,
    ): RawResponse {
        val head = StringBuilder("POST $target HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        if (contentLength != null) head.append("Content-Length: ").append(contentLength).append("\r\n")
        if (authorization != null) head.append("Authorization: ").append(authorization).append("\r\n")
        head.append("\r\n")
        Socket().use { s ->
            s.connect(InetSocketAddress(ComputerApiServer.LOOPBACK, port), timeoutMs)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            if (body.isNotEmpty() && sendBytes > 0) {
                val bytes = body.toByteArray(Charsets.US_ASCII)
                out.write(bytes, 0, minOf(sendBytes, bytes.size))
            }
            out.flush()
            if (shutdownBeforeRead) s.shutdownOutput()
            val resp = try {
                s.getInputStream().readBytes()
            } catch (e: SocketTimeoutException) {
                throw AssertionError(
                    "server did not answer within ${timeoutMs}ms (never read the full body it was told to?)", e,
                )
            }
            return RawResponse.parse(resp)
        }
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

    // ---- slice 2: bodies ----

    @Test
    fun `POST with a body reaches the routes and returns the control's JSON`() {
        control.execResultJson = """{"exit":0,"stdout":"ok"}"""
        val port = server(routes = controlRoutes).start()
        val r = post(port, ComputerApiRoutes.EXEC_PATH, """{"command":"echo hi"}""")
        assertEquals(200, r.status)
        assertEquals("""{"exit":0,"stdout":"ok"}""", String(r.body, Charsets.UTF_8))
        assertEquals("echo hi", org.json.JSONObject(control.execCalls.single()).getString("command"))
        assertEquals("server must deliver the whole body", 1, control.execCalls.size)
    }

    @Test
    fun `POST without a Content-Length header is an empty body to the routes`() {
        val port = server(routes = controlRoutes).start()
        val r = post(port, ComputerApiRoutes.EXEC_PATH, body = "", contentLength = null)
        assertEquals(400, r.status)
        assertEquals(ComputerApiRoutes.JSON, r.header("Content-Type"))
        assertEquals(
            "body must be a JSON object",
            org.json.JSONObject(String(r.body, Charsets.UTF_8)).getString("error"),
        )
        assertTrue(control.approvals.isEmpty())
    }

    @Test
    fun `Content-Length above MAX_BODY_BYTES is 413 and the control is never called`() {
        val port = server(routes = controlRoutes).start()
        val r = post(
            port,
            ComputerApiRoutes.EXEC_PATH,
            body = "x".repeat(4096),
            contentLength = (ComputerApiServer.MAX_BODY_BYTES + 1).toString(),
            sendBytes = 4096,
            timeoutMs = 3_000,
        )
        assertEquals(413, r.status)
        assertTrue(
            "oversize rejections are text/plain, was ${r.header("Content-Type")}",
            r.header("Content-Type")!!.startsWith("text/plain"),
        )
        assertTrue("oversize body must not reach the control", control.execCalls.isEmpty())
        assertTrue(control.approvals.isEmpty())
    }

    @Test
    fun `a negative Content-Length is 400`() {
        val port = server(routes = controlRoutes).start()
        assertEquals(400, post(port, ComputerApiRoutes.EXEC_PATH, body = "", contentLength = "-5").status)
        assertTrue(control.execCalls.isEmpty())
    }

    @Test
    fun `a non-numeric Content-Length is 400`() {
        val port = server(routes = controlRoutes).start()
        assertEquals(400, post(port, ComputerApiRoutes.EXEC_PATH, body = "", contentLength = "abc").status)
        assertTrue(control.execCalls.isEmpty())
    }

    @Test
    fun `a short body followed by EOF is 400`() {
        val port = server(routes = controlRoutes).start()
        val r = post(
            port,
            ComputerApiRoutes.EXEC_PATH,
            body = """{"command":"ls"}""",
            contentLength = "100",
            sendBytes = 10,
            shutdownBeforeRead = true,
        )
        assertEquals(400, r.status)
        assertTrue(control.execCalls.isEmpty())
        assertTrue(control.approvals.isEmpty())
    }

    // ---- slice 3: response observer and host rules ----

    @Test
    fun `onResponse is called with each response status in order`() {
        val statuses = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val port = server(onResponse = { statuses += it }).start()
        assertEquals(200, get(port, ComputerApiRoutes.STATE_PATH).status)
        assertEquals(401, get(port, ComputerApiRoutes.STATE_PATH, authorization = null).status)
        // onResponse fires on the worker thread after the response is written; give it a moment
        Thread.sleep(200)
        assertEquals(listOf(200, 401), statuses)
    }

    @Test
    fun `a LAN host without TLS is still rejected`() {
        val s = server(host = "192.168.1.5")
        assertThrows(IllegalArgumentException::class.java) { s.start() }
        assertFalse(s.isRunning)
    }

    @Test
    fun `a public host with TLS is rejected`() {
        val s = server(host = "8.8.8.8", sslContext = TlsIdentity.generate().serverSslContext())
        assertThrows(IllegalArgumentException::class.java) { s.start() }
    }

    @Test
    fun `the wildcard host with TLS is rejected`() {
        val s = server(host = "0.0.0.0", sslContext = TlsIdentity.generate().serverSslContext())
        assertThrows(IllegalArgumentException::class.java) { s.start() }
    }

    @Test
    fun `the localhost hostname with TLS is rejected`() {
        val s = server(host = "localhost", sslContext = TlsIdentity.generate().serverSslContext())
        assertThrows(IllegalArgumentException::class.java) { s.start() }
    }
}
