package com.xdutsuay.chidori.computer.api

import okhttp3.CertificatePinner
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * Self-signed TLS identity for the Wi-Fi Computer API (slice 3). Clients pin it; no CA.
 *
 * - [generate] makes a new key pair and self-signed certificate with CN [COMMON_NAME]
 *   (okhttp-tls [HeldCertificate]); two calls give different keys.
 * - [publicKeyPin] is `sha256/` + base64 of the SHA-256 of the certificate's
 *   SubjectPublicKeyInfo (OkHttp CertificatePinner format; curl takes it as `sha256//<base64>`).
 * - [certificateSha256] is the SHA-256 of the DER certificate as uppercase hex pairs joined by `:`.
 * - [toPem] / [fromPem] round-trip: the restored identity has the same pin and fingerprint.
 *   [fromPem] throws [IllegalArgumentException] on input that is not a certificate plus key.
 * - [serverSslContext] returns a TLS server context presenting this certificate; a client that
 *   trusts only this certificate completes a handshake against an SSLServerSocket made from it.
 */
class TlsIdentity private constructor(private val held: HeldCertificate) {
    val publicKeyPin: String = CertificatePinner.pin(held.certificate)

    val certificateSha256: String = MessageDigest.getInstance("SHA-256")
        .digest(held.certificate.encoded)
        .joinToString(":") { "%02X".format(it) }

    fun toPem(): String = held.certificatePem() + held.privateKeyPkcs8Pem()

    fun serverSslContext(): SSLContext =
        HandshakeCertificates.Builder().heldCertificate(held).build().sslContext()

    companion object {
        const val COMMON_NAME = "CINDI Computer API"

        /** Pinned, not CA-validated, so a long life only avoids clients rejecting an expired cert. */
        private const val VALIDITY_DAYS = 3650L

        fun generate(): TlsIdentity = TlsIdentity(
            HeldCertificate.Builder()
                .commonName(COMMON_NAME)
                .duration(VALIDITY_DAYS, TimeUnit.DAYS)
                .build(),
        )

        fun fromPem(pem: String): TlsIdentity = try {
            TlsIdentity(HeldCertificate.decode(pem))
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("not a certificate and private key", e)
        }
    }
}
