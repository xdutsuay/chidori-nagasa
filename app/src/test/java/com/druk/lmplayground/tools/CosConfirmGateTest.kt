package com.druk.lmplayground.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * F4 confirm card backend: [CosConfirmGate] blocks the tool until the card is
 * resolved (approve / deny / timeout / Stop), exposing the pending request on a
 * StateFlow for the UI.
 */
class CosConfirmGateTest {

    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** Blocks until the gate exposes a pending request (fail after 5s). */
    private fun awaitPending(gate: CosConfirmGate): CosConfirmGate.Pending {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            gate.pending.value?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("confirm gate never became pending")
    }

    /** Blocks until the gate exposes no pending request (fail after 5s). */
    private fun awaitCleared(gate: CosConfirmGate) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (gate.pending.value == null) return
            Thread.sleep(10)
        }
        throw AssertionError("confirm gate never cleared its pending request")
    }

    @Test
    fun `await blocks until approved and clears pending`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val future = executor.submit<Boolean> { gate.await("cos_gui", "click x=10 y=10") }
        val pending = awaitPending(gate)
        assertEquals("cos_gui", pending.toolName)
        assertEquals("click x=10 y=10", pending.summary)
        gate.resolve(pending.id, approved = true)
        assertTrue("approve resolves to true", future.get(5, TimeUnit.SECONDS))
        assertNull(gate.pending.value)
    }

    @Test
    fun `deny resolves to false and clears pending`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val future = executor.submit<Boolean> { gate.await("cos_gui", "type hello") }
        val pending = awaitPending(gate)
        gate.resolve(pending.id, approved = false)
        assertFalse("deny resolves to false", future.get(5, TimeUnit.SECONDS))
        awaitCleared(gate)
    }

    @Test
    fun `timeout denies and clears pending`() {
        val gate = CosConfirmGate(timeoutMs = 200)
        val future = executor.submit<Boolean> { gate.await("cos_exec", "rm -rf /tmp/build") }
        awaitPending(gate)
        assertFalse("timeout resolves to false", future.get(5, TimeUnit.SECONDS))
        awaitCleared(gate)
    }

    @Test
    fun `cancelAll denies and clears pending`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val future = executor.submit<Boolean> { gate.await("cos_gui", "scroll down") }
        awaitPending(gate)
        gate.cancelAll()
        assertFalse("cancelAll resolves to false", future.get(5, TimeUnit.SECONDS))
        awaitCleared(gate)
    }

    @Test
    fun `resolve with an unknown id is ignored`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val future = executor.submit<Boolean> { gate.await("cos_gui", "click x=1") }
        val pending = awaitPending(gate)
        gate.resolve(pending.id + 999, approved = true)
        assertFalse("unknown id must not resolve the pending request", future.isDone)
        assertEquals("pending stays visible", pending, gate.pending.value)
        gate.resolve(pending.id, approved = true)
        assertTrue(future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `fresh gate has no pending and shared gate exists`() {
        assertNull(CosConfirmGate().pending.value)
        assertNotNull(CosConfirmGate.shared)
    }
}
