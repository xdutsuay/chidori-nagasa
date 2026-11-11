package com.xdutsuay.chidori.computer.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Slice-3 TLS server behaviour, kept out of [ComputerApiServerTest] so the TLS path runs on the
 * plain JVM. Everything binds to 127.0.0.1 with port 0 and clients trust only the test identity.
 */
class ComputerApiServerTlsTest {

    private val identity = TlsIdentity.generate()
    private val bridge = com.xdutsuay.chidori.tools.FakeDesktopBridge()
    private val servers = ArrayList<ComputerApiServer>()

    @After
    fun tearDown() {
        for (s in servers) s.stop()
    }

    private fun newServer(
        routes: ComputerApiRoutes,
        sslContext: javax.net.ssl.SSLContext?,
        onResponse: (Int) -> Unit = {},
    ): ComputerApiServer =
        ComputerApiServer(routes, ComputerApiServer.LOOPBACK, 0, sslContext, onResponse).also { servers += it }

    private val factory: javax.net.ssl.SSLSocketFactory = TestTls.trustingFactory(identity.toPem())

    @Test
    fun `TLS server serves a trusting client and keeps serving after a plain-HTTP probe`() {
        val routes = ComputerApiRoutes("static-token") { bridge }
        val port = newServer(routes, identity.serverSslContext()).start()

        // a plain-HTTP client on the TLS port must not get an HTTP response
        val plain = runCatching {
            Socket().use { ps ->
                ps.connect(InetSocketAddress(ComputerApiServer.LOOPBACK, port), 2_000)
                ps.soTimeout = 3_000
                ps.getOutputStream()
                    .write("GET /v1/computer/state HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
                ps.getInputStream().readBytes()
            }
        }
        assertTrue(
            "plain HTTP must not receive an HTTP response",
            plain.isFailure ||
                !String(plain.getOrDefault(ByteArray(0)), Charsets.ISO_8859_1).startsWith("HTTP/1."),
        )

        // a trusting TLS client still gets served afterwards
        val response = TestTls.exchange(
            factory,
            port,
            TestTls.get(ComputerApiRoutes.STATE_PATH, "static-token"),
        )
        assertEquals(200, TestTls.status(response))
        assertTrue(response.contains("Cache-Control: no-store"))
        assertTrue(response.contains("Connection: close"))
    }

    @Test
    fun `the server reports the client's loopback address as remote`() {
        val pairing = PairingRegistry(
            codeSource = { "123456" },
            tokenSource = { "tok-1" },
            idSource = { "client-1" },
        )
        val routes = ComputerApiRoutes("", pairing = pairing) { bridge }
        val port = newServer(routes, identity.serverSslContext()).start()
        pairing.issueCode()

        val response = TestTls.exchange(
            factory,
            port,
            TestTls.post(ComputerApiRoutes.PAIR_PATH, null, """{"code":"123456"}"""),
        )
        assertEquals(200, TestTls.status(response))
        assertEquals("the routes must see the socket's remote address", "127.0.0.1", pairing.clients.value.single().address)
    }

    @Test
    fun `a paired token then reads state over the same TLS connection factory`() {
        val pairing = PairingRegistry(
            codeSource = { "123456" },
            tokenSource = { "tok-1" },
            idSource = { "client-1" },
        )
        val routes = ComputerApiRoutes("", pairing = pairing) { bridge }
        val port = newServer(routes, identity.serverSslContext()).start()
        pairing.issueCode()

        val pair = TestTls.exchange(
            factory,
            port,
            TestTls.post(ComputerApiRoutes.PAIR_PATH, null, """{"code":"123456"}"""),
        )
        assertEquals(200, TestTls.status(pair))
        val token = TestTls.jsonStringField(pair.substringAfter("\r\n\r\n"), "token")
        assertEquals("tok-1", token)
        val state = TestTls.exchange(factory, port, TestTls.get(ComputerApiRoutes.STATE_PATH, token))
        assertEquals(200, TestTls.status(state))
    }

    @Test
    fun `onResponse observes 200 and 401 on the TLS server too`() {
        val statuses = CopyOnWriteArrayList<Int>()
        val routes = ComputerApiRoutes("static-token") { bridge }
        val port = newServer(routes, identity.serverSslContext()) { statuses += it }.start()

        assertEquals(200, TestTls.status(TestTls.exchange(factory, port, TestTls.get(ComputerApiRoutes.STATE_PATH, "static-token"))))
        assertEquals(401, TestTls.status(TestTls.exchange(factory, port, TestTls.get(ComputerApiRoutes.STATE_PATH, null))))
        Thread.sleep(200)
        assertEquals(listOf(200, 401), statuses)
    }
}
