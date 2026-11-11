package com.xdutsuay.chidori.computer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec tests for [PairingRegistry]: injectable nowMs/code/token/id sources, TTL expiry,
 * single-use codes, wrong-code counting and lockout, client bookkeeping, and randomCode.
 */
class PairingRegistryTest {

    private var now = 1_000L
    private val codes = ArrayDeque(listOf("111111", "222222", "333333"))
    private val tokens = ArrayDeque(listOf("tok-a", "tok-b", "tok-c"))
    private val ids = ArrayDeque(listOf("id-1", "id-2", "id-3"))

    private fun registry(
        codeTtlMs: Long = PairingRegistry.DEFAULT_CODE_TTL_MS,
        maxAttempts: Int = PairingRegistry.DEFAULT_MAX_ATTEMPTS,
    ): PairingRegistry = PairingRegistry(
        nowMs = { now },
        codeSource = { codes.removeFirst() },
        tokenSource = { tokens.removeFirst() },
        idSource = { ids.removeFirst() },
        codeTtlMs = codeTtlMs,
        maxAttempts = maxAttempts,
    )

    // ---- issueCode / randomCode ----

    @Test
    fun `issueCode exposes the injected code valid for codeTtlMs`() {
        val r = registry(codeTtlMs = 5_000)
        val pc = r.issueCode()
        assertEquals("111111", pc.code)
        assertEquals(6_000L, pc.expiresAtMs)
        assertEquals(pc, r.code.value)
    }

    @Test
    fun `randomCode is 6 digits`() {
        repeat(50) {
            val c = PairingRegistry.randomCode()
            assertEquals(6, c.length)
            c.forEach { ch -> assertTrue("bad digit '$ch'", ch in '0'..'9') }
        }
    }

    // ---- redeem ----

    @Test
    fun `the right code succeeds once, registers the client and is cleared`() {
        val r = registry()
        r.issueCode()
        now = 1_500
        val ok = r.redeem("111111", "  Pixel  ", "192.168.1.5")
        assertTrue(ok is Redeem.Ok)
        ok as Redeem.Ok
        assertEquals("tok-a", ok.token)
        assertEquals("id-1", ok.client.id)
        assertEquals("Pixel", ok.client.name)
        assertEquals("192.168.1.5", ok.client.address)
        assertEquals(1_500, ok.client.pairedAtMs)
        assertEquals(1_500, ok.client.lastSeenMs)
        assertEquals(listOf(ok.client), r.clients.value)
        assertNull("a used code is cleared", r.code.value)
        assertEquals("single use: the same code must not work twice", Redeem.Rejected, r.redeem("111111", "x", "y"))
    }

    @Test
    fun `wrong codes count and lock out even the right code`() {
        val r = registry(maxAttempts = 3)
        r.issueCode()
        repeat(3) { assertEquals(Redeem.Rejected, r.redeem("999999", "n", "a")) }
        assertNull("lockout clears the code", r.code.value)
        assertEquals("even the right code is now rejected", Redeem.Rejected, r.redeem("111111", "n", "a"))
        assertTrue(r.clients.value.isEmpty())

        // a fresh code works again, and failed attempts consumed no tokens
        r.issueCode()
        val ok = r.redeem("222222", "n", "a")
        assertEquals("tok-a", (ok as Redeem.Ok).token)
    }

    @Test
    fun `codes expire after codeTtlMs and expiry clears the code`() {
        val r = registry(codeTtlMs = 5_000)
        now = 1_000
        r.issueCode()
        now = 5_999 // one ms before expiry
        assertTrue(r.redeem("111111", "n", "a") is Redeem.Ok)

        r.issueCode() // 222222, expires at 5999+5000 = 10999
        now = 11_000 // after expiry
        assertEquals(Redeem.Rejected, r.redeem("222222", "n", "a"))
        assertNull("expired code is cleared", r.code.value)
    }

    @Test
    fun `issueCode replaces the current code and resets failed attempts`() {
        val r = registry(maxAttempts = 5)
        r.issueCode()
        r.redeem("000000", "n", "a")
        r.redeem("000001", "n", "a") // two failures against the old code

        r.issueCode() // replaces 111111 with 222222 and resets the count
        assertEquals(Redeem.Rejected, r.redeem("111111", "n", "a")) // the old code is gone

        // three more failures: would be 5 total if the count had not been reset
        repeat(3) { assertEquals(Redeem.Rejected, r.redeem("999999", "n", "a")) }
        assertTrue("attempts must have been reset by issueCode", r.redeem("222222", "n", "a") is Redeem.Ok)
    }

    // ---- name handling ----

    @Test
    fun `redeem trims the name and cuts it to MAX_NAME_CHARS`() {
        val r = registry()
        r.issueCode()
        val ok = r.redeem("111111", "abcdefghij".repeat(5), "a") as Redeem.Ok // 50 chars
        assertEquals(40, ok.client.name.length)
    }

    @Test
    fun `a blank name becomes DEFAULT_NAME`() {
        val r = registry()
        r.issueCode()
        val ok = r.redeem("111111", "   ", "a") as Redeem.Ok
        assertEquals(PairingRegistry.DEFAULT_NAME, ok.client.name)
    }

    // ---- authenticate ----

    @Test
    fun `authenticate with a known token updates lastSeenMs`() {
        val r = registry()
        r.issueCode()
        val ok = r.redeem("111111", "n", "a") as Redeem.Ok

        now = 2_000
        assertEquals(2_000, r.authenticate(ok.token)!!.lastSeenMs)
        now = 3_000
        assertEquals(3_000, r.authenticate(ok.token)!!.lastSeenMs)
    }

    @Test
    fun `authenticate rejects unknown and empty tokens`() {
        val r = registry()
        r.issueCode()
        r.redeem("111111", "n", "a")
        assertNull(r.authenticate("totally-unknown"))
        assertNull(r.authenticate(""))
    }

    // ---- revoke ----

    @Test
    fun `revoke removes one client in pairing order and kills only its token`() {
        val r = registry()
        r.issueCode()
        val first = r.redeem("111111", "one", "a") as Redeem.Ok
        r.issueCode()
        val second = r.redeem("222222", "two", "b") as Redeem.Ok
        assertEquals(listOf(first.client, second.client), r.clients.value)

        r.revoke(first.client.id)
        assertEquals(listOf(second.client), r.clients.value)
        assertNull(r.authenticate(first.token))
        assertEquals(second.client, r.authenticate(second.token))
    }

    @Test
    fun `revokeAll removes all clients and clears the code`() {
        val r = registry()
        r.issueCode()
        val first = r.redeem("111111", "one", "a") as Redeem.Ok
        r.issueCode()
        r.redeem("222222", "two", "b") as Redeem.Ok
        r.issueCode() // a live code on the shelf

        r.revokeAll()
        assertTrue(r.clients.value.isEmpty())
        assertNull(r.code.value)
        assertNull(r.authenticate(first.token))
    }
}
