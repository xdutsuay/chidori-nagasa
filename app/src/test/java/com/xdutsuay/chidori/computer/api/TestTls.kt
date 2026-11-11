package com.xdutsuay.chidori.computer.api

import okhttp3.tls.HandshakeCertificates
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Slice-3 TLS test helpers: parse the certificate out of a [TlsIdentity] PEM (an independent
 * oracle — the tests never reach into the identity's internals), build clients that trust
 * exactly that one certificate, and exchange raw HTTP/1.1 over TLS on loopback.
 */
object TestTls {

    fun certificate(pem: String): X509Certificate {
        val body = pem
            .substringAfter("-----BEGIN CERTIFICATE-----")
            .substringBefore("-----END CERTIFICATE-----")
        require(body.isNotBlank()) { "no CERTIFICATE block in PEM" }
        val der = Base64.getMimeDecoder().decode(body)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** A socket factory that trusts exactly the certificate inside [pem] (and nothing else). */
    fun trustingFactory(pem: String): SSLSocketFactory =
        HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate(pem))
            .build()
            .sslContext()
            .socketFactory

    /** One HTTP/1.1 exchange over TLS; reads to EOF (the server sends Connection: close). */
    fun exchange(factory: SSLSocketFactory, port: Int, request: String): String =
        (factory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
            s.startHandshake()
            s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
        }

    fun get(target: String, token: String?): String =
        "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            (if (token != null) "Authorization: Bearer $token\r\n" else "") +
            "\r\n"

    fun post(target: String, token: String?, body: String): String =
        "POST $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            (if (token != null) "Authorization: Bearer $token\r\n" else "") +
            "\r\n" +
            body

    fun status(response: String): Int =
        response.substringBefore("\r\n").substringAfter(' ').substringBefore(' ').toInt()

    /** Pulls a JSON string field out of a flat response body without org.json. */
    fun jsonStringField(body: String, field: String): String =
        Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]
}
