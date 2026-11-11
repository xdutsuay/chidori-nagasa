package com.xdutsuay.chidori.computer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Spec tests for [TlsIdentity]: self-signed identity with pin/fingerprint, PEM round-trip,
 * and a real TLS handshake against an [SSLServerSocket] built from [TlsIdentity.serverSslContext].
 * All sockets bind to 127.0.0.1 on an ephemeral port only.
 */
class TlsIdentityTest {

    // ---- pin / fingerprint formats ----

    @Test
    fun `publicKeyPin is sha256 slash plus base64 of 32 bytes`() {
        val pin = TlsIdentity.generate().publicKeyPin
        assertTrue("pin must start with 'sha256/': $pin", pin.startsWith("sha256/"))
        val b64 = pin.removePrefix("sha256/")
        assertEquals("base64 of 32 bytes is 44 chars", 44, b64.length)
        assertTrue("must be standard base64: $b64", b64.matches(Regex("[A-Za-z0-9+/]+=")))
        assertEquals("decodes to 32 bytes", 32, Base64.getDecoder().decode(b64).size)
    }

    @Test
    fun `certificateSha256 is 32 uppercase hex pairs joined by colon`() {
        val fp = TlsIdentity.generate().certificateSha256
        assertEquals("32 pairs + 31 separators = 95 chars", 95, fp.length)
        val pairs = fp.split(":")
        assertEquals(32, pairs.size)
        pairs.forEach { p ->
            assertTrue("each pair must be two uppercase hex digits: '$p'", p.matches(Regex("[0-9A-F]{2}")))
        }
        assertFalse(fp.lowercase() == fp)
    }

    @Test
    fun `generate twice produces different identities`() {
        val a = TlsIdentity.generate()
        val b = TlsIdentity.generate()
        assertFalse("keys must differ", a.publicKeyPin == b.publicKeyPin)
        assertFalse(a.certificateSha256 == b.certificateSha256)
    }

    // ---- PEM round-trip ----

    @Test
    fun `PEM round-trip keeps pin and fingerprint`() {
        val original = TlsIdentity.generate()
        val restored = TlsIdentity.fromPem(original.toPem())
        assertEquals(original.publicKeyPin, restored.publicKeyPin)
        assertEquals(original.certificateSha256, restored.certificateSha256)
    }

    @Test
    fun `fromPem rejects garbage with IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { TlsIdentity.fromPem("hello world") }
        assertThrows(IllegalArgumentException::class.java) { TlsIdentity.fromPem("") }
        assertThrows(IllegalArgumentException::class.java) {
            TlsIdentity.fromPem("-----BEGIN CERTIFICATE-----\nZmFyYmFnZQ==\n-----END CERTIFICATE-----")
        }
    }

    // ---- certificate content ----

    @Test
    fun `the certificate's CN is COMMON_NAME`() {
        val cert = TestTls.certificate(TlsIdentity.generate().toPem())
        assertEquals("CN=${TlsIdentity.COMMON_NAME}", cert.subjectX500Principal.name)
    }

    // ---- real handshakes ----

    /** Accepts one TLS connection, reads a byte and echoes it +1 back. */
    private fun serveOneEcho(identity: TlsIdentity): SSLServerSocket {
        val ss = identity.serverSslContext()
            .serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName(ComputerApiServer.LOOPBACK)) as SSLServerSocket
        thread(isDaemon = true) {
            try {
                (ss.accept() as SSLSocket).use { s ->
                    s.soTimeout = 5_000
                    s.startHandshake()
                    val b = s.getInputStream().read()
                    s.getOutputStream().write((b + 1) and 0xff)
                }
            } catch (_: Throwable) {
                // client-side failures are the client test's concern
            }
        }
        return ss
    }

    @Test
    fun `a client trusting only this identity completes a handshake`() {
        val id = TlsIdentity.generate()
        val ss = serveOneEcho(id)
        try {
            val client = TestTls.trustingFactory(id.toPem())
                .createSocket(ComputerApiServer.LOOPBACK, ss.localPort) as SSLSocket
            client.use { c ->
                c.startHandshake()
                c.getOutputStream().write(41)
                assertEquals("server must see the handshake too", 42, c.getInputStream().read())
            }
        } finally {
            ss.close()
        }
    }

    @Test
    fun `a client trusting a different identity fails the handshake`() {
        val serverIdentity = TlsIdentity.generate()
        val other = TlsIdentity.generate()
        val ss = serveOneEcho(serverIdentity)
        try {
            val client = TestTls.trustingFactory(other.toPem())
                .createSocket(ComputerApiServer.LOOPBACK, ss.localPort) as SSLSocket
            assertThrows(java.io.IOException::class.java) { client.startHandshake() }
        } finally {
            ss.close()
        }
    }
}
