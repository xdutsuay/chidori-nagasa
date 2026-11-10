package com.druk.lmplayground.computer.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class HostKeyPolicyTest {

    @Test
    fun `null pinned fingerprint yields trust on first use`() {
        assertEquals(
            HostKeyPolicy.Decision.TRUST_ON_FIRST_USE,
            HostKeyPolicy.decide(null, "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"),
        )
    }

    @Test
    fun `blank pinned fingerprint yields trust on first use`() {
        assertEquals(
            HostKeyPolicy.Decision.TRUST_ON_FIRST_USE,
            HostKeyPolicy.decide("", "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"),
        )
        assertEquals(
            HostKeyPolicy.Decision.TRUST_ON_FIRST_USE,
            HostKeyPolicy.decide("   ", "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"),
        )
    }

    @Test
    fun `equal fingerprints yield match`() {
        assertEquals(
            HostKeyPolicy.Decision.MATCH,
            HostKeyPolicy.decide("SHA256:abc123", "SHA256:abc123"),
        )
    }

    @Test
    fun `different fingerprints yield mismatch`() {
        assertEquals(
            HostKeyPolicy.Decision.MISMATCH,
            HostKeyPolicy.decide("SHA256:abc123", "SHA256:def456"),
        )
    }

    @Test
    fun `comparison is case sensitive`() {
        assertEquals(
            HostKeyPolicy.Decision.MISMATCH,
            HostKeyPolicy.decide("SHA256:ABC", "SHA256:abc"),
        )
    }

    @Test
    fun `presented key is never treated as blank`() {
        // Only the pinned side is null/blank-checked; presented is always compared literally.
        assertEquals(
            HostKeyPolicy.Decision.MISMATCH,
            HostKeyPolicy.decide("SHA256:abc", ""),
        )
    }
}
