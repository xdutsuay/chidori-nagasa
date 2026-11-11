package com.xdutsuay.chidori.coordinator.node

import com.xdutsuay.chidori.coordinator.model.InstanceId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CP5 unit tests for [NodePeerPolicy] (test author only — cross-model
 * separation; production authored in a separate session).
 *
 * Covers the pairing allowlist gate and the single-use register nonce
 * ReplayGuard semantics:
 * 1. unpaired instance rejected
 * 2. paired instance accepted
 * 3. empty nonce claim rejected
 * 4. same nonce claimed twice — second claim rejected
 * 5. distinct nonces each claim once
 */
class NodePeerPolicyTest {

    // -- allowlist: assertPaired -------------------------------------------------

    @Test
    fun `unpaired instance is rejected`() {
        try {
            NodePeerPolicy.assertPaired(InstanceId("desktop-b"), setOf("desktop-a"))
            fail("assertPaired must throw for an unpaired instance")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "error should name the offending instance",
                expected.message!!.contains("desktop-b"),
            )
        }
    }

    @Test
    fun `paired instance passes allowlist`() {
        // Must not throw.
        NodePeerPolicy.assertPaired(InstanceId("desktop-a"), setOf("desktop-a", "desktop-b"))
    }

    // -- ReplayGuard -------------------------------------------------------------

    @Test
    fun `empty nonce claim is rejected`() {
        val guard = NodePeerPolicy.ReplayGuard()
        assertFalse("empty nonce must be rejected", guard.claim(""))
        assertFalse("blank nonce must be rejected", guard.claim("   "))
    }

    @Test
    fun `same nonce claimed twice - second claim rejected`() {
        val guard = NodePeerPolicy.ReplayGuard()
        assertTrue("first claim of a fresh nonce must succeed", guard.claim("nonce-1"))
        assertFalse("replayed nonce must be rejected", guard.claim("nonce-1"))
    }

    @Test
    fun `distinct nonces each claim once`() {
        val guard = NodePeerPolicy.ReplayGuard()
        assertTrue(guard.claim("nonce-a"))
        assertTrue(guard.claim("nonce-b"))
        // Guard is single-use per nonce; a third distinct nonce is still fresh.
        assertTrue(guard.claim("nonce-c"))
        // And each earlier one stays spent.
        assertFalse(guard.claim("nonce-a"))
        assertFalse(guard.claim("nonce-b"))
    }

    @Test
    fun `clear resets spent nonces`() {
        val guard = NodePeerPolicy.ReplayGuard()
        assertTrue(guard.claim("nonce-x"))
        guard.clear()
        assertTrue("clear must allow the nonce to be claimed again", guard.claim("nonce-x"))
    }
}
