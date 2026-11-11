package com.xdutsuay.chidori.computer.api

import com.xdutsuay.chidori.tools.CosConfirmGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ApiApprovalRelay] mirrors the pending Computer API approval of a [CosConfirmGate] onto a
 * notification (via [ApiApprovalRelay.Poster]) and answers the gate from its Allow / Deny actions.
 */
class ApiApprovalRelayTest {

    private class FakePoster : ApiApprovalRelay.Poster {
        val shown: MutableList<ApiApprovalRelay.Request> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<ApiApprovalRelay.Request> = Collections.synchronizedList(mutableListOf())
        @Volatile var throwOnShow = false
        @Volatile var throwOnCancel = false

        override fun show(request: ApiApprovalRelay.Request) {
            shown += request
            if (throwOnShow) throw IllegalStateException("show failed")
        }

        override fun cancel(request: ApiApprovalRelay.Request) {
            cancelled += request
            if (throwOnCancel) throw IllegalStateException("cancel failed")
        }
    }

    private val poster = FakePoster()
    private val resolves: MutableList<Pair<Long, Boolean>> = Collections.synchronizedList(mutableListOf())
    private val keyCounter = AtomicInteger(0)
    private val executor = Executors.newCachedThreadPool()

    private fun relay(
        newKey: () -> String = { "key-${keyCounter.incrementAndGet()}" },
        resolve: (Long, Boolean) -> Unit = { id, approved -> resolves += id to approved },
    ) = ApiApprovalRelay(resolve = resolve, poster = poster, newKey = newKey)

    private fun api(id: Long, action: String = "exec", summary: String = "ls -la /sdcard") =
        CosConfirmGate.Pending(id, "${ToolComputerControl.APPROVAL_PREFIX}$action", summary)

    private fun chat(id: Long) = CosConfirmGate.Pending(id, "cos_exec", "rm -rf build")

    @After
    fun tearDown() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    // --- onPending: showing ---

    @Test
    fun `API pending is shown once with a fresh key and the action without the prefix`() {
        val relay = relay()

        relay.onPending(api(7, action = "gui", summary = "tap 10,20"))

        val expected = ApiApprovalRelay.Request(key = "key-1", gateId = 7, action = "gui", summary = "tap 10,20")
        assertEquals(listOf(expected), poster.shown)
        assertEquals(expected, relay.current())
        assertTrue(poster.cancelled.isEmpty())
        assertTrue(resolves.isEmpty())
    }

    @Test
    fun `same gate id again is a no-op with the same key`() {
        val relay = relay()
        relay.onPending(api(7))
        val first = relay.current()

        relay.onPending(api(7))
        relay.onPending(api(7))

        assertEquals(1, poster.shown.size)
        assertEquals(first, relay.current())
        assertEquals(1, keyCounter.get())
        assertTrue(poster.cancelled.isEmpty())
    }

    @Test
    fun `chat tool confirmation without the prefix is not shown`() {
        val relay = relay()

        relay.onPending(chat(3))

        assertTrue(poster.shown.isEmpty())
        assertNull(relay.current())
        assertTrue(resolves.isEmpty())
    }

    @Test
    fun `initial null pending does nothing`() {
        val relay = relay()

        relay.onPending(null)

        assertTrue(poster.shown.isEmpty())
        assertTrue(poster.cancelled.isEmpty())
        assertNull(relay.current())
    }

    // --- onPending: gate moving on ---

    @Test
    fun `null after an API request cancels it and its key stops working`() {
        val relay = relay()
        relay.onPending(api(7))
        val shown = relay.current()!!

        relay.onPending(null)

        assertEquals(listOf(shown), poster.cancelled)
        assertNull(relay.current())
        assertFalse(relay.answer(shown.key, true))
        assertTrue(resolves.isEmpty())
        assertEquals(1, poster.cancelled.size)
    }

    @Test
    fun `different gate id cancels the previous request and shows the new one with a new key`() {
        val relay = relay()
        relay.onPending(api(7, action = "exec"))
        val old = relay.current()!!

        relay.onPending(api(8, action = "gui", summary = "type hello"))

        val new = relay.current()!!
        assertEquals(listOf(old), poster.cancelled)
        assertEquals(listOf(old, new), poster.shown)
        assertEquals(8L, new.gateId)
        assertEquals("gui", new.action)
        assertNotEquals(old.key, new.key)
        assertFalse(relay.answer(old.key, true))
        assertTrue(resolves.isEmpty())
    }

    @Test
    fun `non-API pending after an API request cancels it and is not shown`() {
        val relay = relay()
        relay.onPending(api(7))
        val old = relay.current()!!

        relay.onPending(chat(8))

        assertEquals(listOf(old), poster.cancelled)
        assertEquals(1, poster.shown.size)
        assertNull(relay.current())
        assertFalse(relay.answer(old.key, false))
        assertTrue(resolves.isEmpty())
    }

    // --- answer ---

    @Test
    fun `answer allow with the current key cancels and resolves approved exactly once`() {
        val relay = relay()
        relay.onPending(api(7))
        val request = relay.current()!!

        assertTrue(relay.answer(request.key, true))

        assertEquals(listOf(7L to true), resolves)
        assertEquals(listOf(request), poster.cancelled)
        assertNull(relay.current())
    }

    @Test
    fun `answer deny with the current key cancels and resolves denied exactly once`() {
        val relay = relay()
        relay.onPending(api(9))
        val request = relay.current()!!

        assertTrue(relay.answer(request.key, false))

        assertEquals(listOf(9L to false), resolves)
        assertEquals(listOf(request), poster.cancelled)
        assertNull(relay.current())
    }

    @Test
    fun `second answer with the same key returns false and does nothing more`() {
        val relay = relay()
        relay.onPending(api(7))
        val key = relay.current()!!.key
        assertTrue(relay.answer(key, true))

        assertFalse(relay.answer(key, true))
        assertFalse(relay.answer(key, false))

        assertEquals(listOf(7L to true), resolves)
        assertEquals(1, poster.cancelled.size)
    }

    @Test
    fun `gate clearing after an answer does not cancel the notification again`() {
        val relay = relay()
        relay.onPending(api(7))
        relay.answer(relay.current()!!.key, true)

        relay.onPending(null)

        assertEquals(1, poster.cancelled.size)
        assertEquals(1, resolves.size)
    }

    @Test
    fun `unknown blank and null keys return false without resolve or cancel`() {
        val relay = relay()
        relay.onPending(api(7))
        val request = relay.current()!!

        assertFalse(relay.answer(null, true))
        assertFalse(relay.answer("", true))
        assertFalse(relay.answer("   ", true))
        assertFalse(relay.answer("not-a-key", true))
        assertFalse(relay.answer("not-a-key", false))

        assertTrue(resolves.isEmpty())
        assertTrue(poster.cancelled.isEmpty())
        assertEquals(request, relay.current())
    }

    @Test
    fun `answer with nothing current returns false`() {
        val relay = relay()

        assertFalse(relay.answer("key-1", true))

        assertTrue(resolves.isEmpty())
        assertTrue(poster.cancelled.isEmpty())
    }

    @Test
    fun `stale key from a replaced request does not touch the current request`() {
        val relay = relay()
        relay.onPending(api(7))
        val stale = relay.current()!!.key
        relay.onPending(api(8))
        val current = relay.current()!!
        val cancelsBefore = poster.cancelled.size

        assertFalse(relay.answer(stale, true))

        assertTrue(resolves.isEmpty())
        assertEquals(cancelsBefore, poster.cancelled.size)
        assertEquals(current, relay.current())
    }

    @Test
    fun `key comes from newKey and is not derived from the gate id`() {
        val relay = relay(newKey = { "opaque-token" })

        relay.onPending(api(7))

        assertEquals("opaque-token", relay.current()!!.key)
        assertFalse(relay.answer("7", true))
        assertFalse(relay.answer("1", true))
        assertTrue(resolves.isEmpty())
        assertTrue(relay.answer("opaque-token", true))
        assertEquals(listOf(7L to true), resolves)
    }

    @Test
    fun `fresh relay with the same gate id gets a different key than a previous relay`() {
        val first = relay()
        first.onPending(api(1))
        val oldKey = first.current()!!.key

        val second = relay()
        second.onPending(api(1))

        assertNotEquals(oldKey, second.current()!!.key)
        assertFalse(second.answer(oldKey, true))
    }

    // --- sharingStopped ---

    @Test
    fun `sharingStopped denies and cancels the current request once`() {
        val relay = relay()
        relay.onPending(api(7))
        val request = relay.current()!!

        relay.sharingStopped()
        relay.sharingStopped()

        assertEquals(listOf(7L to false), resolves)
        assertEquals(listOf(request), poster.cancelled)
        assertNull(relay.current())
        assertFalse(relay.answer(request.key, true))
        assertEquals(listOf(7L to false), resolves)
    }

    @Test
    fun `sharingStopped with nothing current is a no-op`() {
        val relay = relay()

        relay.sharingStopped()

        assertTrue(resolves.isEmpty())
        assertTrue(poster.cancelled.isEmpty())
        assertTrue(poster.shown.isEmpty())
    }

    @Test
    fun `sharingStopped after an answer does nothing`() {
        val relay = relay()
        relay.onPending(api(7))
        relay.answer(relay.current()!!.key, true)

        relay.sharingStopped()

        assertEquals(listOf(7L to true), resolves)
        assertEquals(1, poster.cancelled.size)
    }

    @Test
    fun `sharingStopped with only a chat confirmation pending does not resolve it`() {
        val relay = relay()
        relay.onPending(chat(4))

        relay.sharingStopped()

        assertTrue(resolves.isEmpty())
        assertTrue(poster.cancelled.isEmpty())
    }

    // --- errors default to denial ---

    @Test
    fun `show throwing is swallowed never approves and the relay keeps working`() {
        val relay = relay()
        poster.throwOnShow = true

        relay.onPending(api(7))

        assertTrue(resolves.none { it.second })

        poster.throwOnShow = false
        relay.onPending(api(8))
        val request = relay.current()
        assertNotNull(request)
        assertEquals(8L, request!!.gateId)
        assertTrue(relay.answer(request.key, false))
        assertTrue(resolves.none { it.second })
        assertTrue(resolves.contains(8L to false))
    }

    @Test
    fun `cancel throwing on gate change is swallowed and the next request still shows`() {
        val relay = relay()
        relay.onPending(api(7))
        val old = relay.current()!!
        poster.throwOnCancel = true

        relay.onPending(api(8))

        assertTrue(resolves.isEmpty())
        assertFalse(relay.answer(old.key, true))
        assertTrue(resolves.isEmpty())
        val current = relay.current()!!
        assertEquals(8L, current.gateId)
        assertEquals(listOf(old, current), poster.shown)
    }

    @Test
    fun `cancel throwing on null pending is swallowed`() {
        val relay = relay()
        relay.onPending(api(7))
        poster.throwOnCancel = true

        relay.onPending(null)

        assertTrue(resolves.isEmpty())
        assertNull(relay.current())
    }

    @Test
    fun `cancel throwing during sharingStopped still only denies`() {
        val relay = relay()
        relay.onPending(api(7))
        poster.throwOnCancel = true

        relay.sharingStopped()

        assertTrue(resolves.none { it.second })
        assertTrue(resolves.size <= 1)
        assertNull(relay.current())
    }

    @Test
    fun `cancel throwing during answer does not throw and resolves at most once`() {
        val relay = relay()
        relay.onPending(api(7))
        val key = relay.current()!!.key
        poster.throwOnCancel = true

        relay.answer(key, true)
        relay.answer(key, true)

        assertTrue(resolves.size <= 1)
        assertTrue(resolves.all { it.first == 7L })
    }

    // --- thread safety ---

    @Test
    fun `concurrent answers with the same key resolve exactly once`() {
        val relay = relay()
        relay.onPending(api(7))
        val key = relay.current()!!.key
        val threads = 32
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val wins = AtomicInteger(0)

        val futures = (0 until threads).map { i ->
            executor.submit {
                ready.countDown()
                go.await()
                if (relay.answer(key, i % 2 == 0)) wins.incrementAndGet()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        futures.forEach { it.get(5, TimeUnit.SECONDS) }

        assertEquals(1, wins.get())
        assertEquals(1, resolves.size)
        assertEquals(7L, resolves.single().first)
        assertEquals(1, poster.cancelled.size)
    }

    @Test
    fun `concurrent answer and sharingStopped resolve exactly once`() {
        repeat(50) { round ->
            resolves.clear()
            poster.cancelled.clear()
            val relay = relay()
            relay.onPending(api(round.toLong() + 1))
            val key = relay.current()!!.key
            val go = CountDownLatch(1)

            val a = executor.submit { go.await(); relay.answer(key, true) }
            val b = executor.submit { go.await(); relay.sharingStopped() }
            go.countDown()
            a.get(5, TimeUnit.SECONDS)
            b.get(5, TimeUnit.SECONDS)

            assertEquals("round $round", 1, resolves.size)
            assertEquals("round $round", 1, poster.cancelled.size)
        }
    }

    // --- with a real CosConfirmGate ---

    private fun awaitPending(gate: CosConfirmGate): CosConfirmGate.Pending {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            gate.pending.value?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("confirm gate never became pending")
    }

    @Test
    fun `allow through the relay makes a real gate await return true`() {
        val gate = CosConfirmGate(timeoutMs = 5_000)
        val relay = relay(resolve = { id, approved -> resolves += id to approved; gate.resolve(id, approved) })
        val result = executor.submit<Boolean> {
            gate.await("${ToolComputerControl.APPROVAL_PREFIX}exec", "ls")
        }

        relay.onPending(awaitPending(gate))
        val request = relay.current()!!
        assertEquals("exec", request.action)
        assertEquals("ls", request.summary)
        assertTrue(relay.answer(request.key, true))

        assertTrue(result.get(5, TimeUnit.SECONDS))
        relay.onPending(gate.pending.value)
        assertEquals(1, poster.cancelled.size)
    }

    @Test
    fun `deny through the relay makes a real gate await return false`() {
        val gate = CosConfirmGate(timeoutMs = 5_000)
        val relay = relay(resolve = gate::resolve)
        val result = executor.submit<Boolean> {
            gate.await("${ToolComputerControl.APPROVAL_PREFIX}gui", "tap")
        }

        relay.onPending(awaitPending(gate))
        assertTrue(relay.answer(relay.current()!!.key, false))

        assertFalse(result.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `sharingStopped makes a real gate await return false`() {
        val gate = CosConfirmGate(timeoutMs = 5_000)
        val relay = relay(resolve = gate::resolve)
        val result = executor.submit<Boolean> {
            gate.await("${ToolComputerControl.APPROVAL_PREFIX}exec", "ls")
        }

        relay.onPending(awaitPending(gate))
        relay.sharingStopped()

        assertFalse(result.get(5, TimeUnit.SECONDS))
    }

    // --- companion helpers ---

    @Test
    fun `isApiRequest is true only for the Computer API prefix`() {
        assertTrue(ApiApprovalRelay.isApiRequest(api(1, action = "exec")))
        assertTrue(ApiApprovalRelay.isApiRequest(api(1, action = "gui")))
        assertFalse(ApiApprovalRelay.isApiRequest(chat(1)))
        assertFalse(ApiApprovalRelay.isApiRequest(CosConfirmGate.Pending(1, "Computer API:exec", "x")))
        assertFalse(ApiApprovalRelay.isApiRequest(CosConfirmGate.Pending(1, "cos_gui Computer API: exec", "x")))
        assertFalse(ApiApprovalRelay.isApiRequest(CosConfirmGate.Pending(1, "", "x")))
    }

    @Test
    fun `actionOf strips the Computer API prefix`() {
        assertEquals("exec", ApiApprovalRelay.actionOf(api(1, action = "exec")))
        assertEquals("gui", ApiApprovalRelay.actionOf(api(1, action = "gui")))
    }
}
