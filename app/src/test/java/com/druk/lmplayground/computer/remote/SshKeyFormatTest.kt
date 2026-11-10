package com.druk.lmplayground.computer.remote

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyFormatTest {

    private fun expectedFingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }

    @Test
    fun `empty blob produces the known SHA256 fingerprint`() {
        assertEquals(
            "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU",
            SshKeyFormat.fingerprintSha256(ByteArray(0)),
        )
    }

    @Test
    fun `fingerprint matches locally computed SHA256 base64`() {
        val blob = ByteArray(64) { i -> (i * 7 + 3).toByte() }

        val fingerprint = SshKeyFormat.fingerprintSha256(blob)

        assertEquals(expectedFingerprint(blob), fingerprint)
        assertTrue(fingerprint.startsWith("SHA256:"))
        assertFalse("padding must be stripped", fingerprint.contains('='))
    }

    @Test
    fun `fingerprint uses standard base64 alphabet not url safe`() {
        // SHA-256 of "hello\n" is 2 bytes... use a blob whose digest contains '+' and '/'
        // candidates in standard base64; verify against the standard encoder directly.
        val blob = "known test blob for chidori remote computer".toByteArray(Charsets.UTF_8)

        val fingerprint = SshKeyFormat.fingerprintSha256(blob)

        assertEquals(expectedFingerprint(blob), fingerprint)
        assertFalse(fingerprint.contains('-'))
        assertFalse(fingerprint.contains('_'))
    }

    @Test
    fun `authorizedKeysLine joins type blob and comment`() {
        val blob = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(blob)

        assertEquals("ssh-ed25519 $b64 user@host", SshKeyFormat.authorizedKeysLine("ssh-ed25519", blob, "user@host"))
    }

    @Test
    fun `authorizedKeysLine keeps base64 padding`() {
        // 4 bytes -> base64 has one '=' padding char; it must be preserved here.
        val blob = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(blob)

        assertTrue(b64.endsWith("="))
        assertTrue(SshKeyFormat.authorizedKeysLine("ssh-rsa", blob, "c").contains(b64))
    }

    @Test
    fun `authorizedKeysLine drops trailing space for blank comment`() {
        val blob = byteArrayOf(9, 8, 7)
        val b64 = Base64.getEncoder().encodeToString(blob)

        assertEquals("ssh-ed25519 $b64", SshKeyFormat.authorizedKeysLine("ssh-ed25519", blob, ""))
        assertEquals("ssh-ed25519 $b64", SshKeyFormat.authorizedKeysLine("ssh-ed25519", blob, "   "))
    }
}
