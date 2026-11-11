package com.xdutsuay.chidori.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosGuestDnsTest {

    @Test
    fun `link DNS preferred over fallback`() {
        assertEquals(
            listOf("192.168.1.1", "1.0.0.1"),
            CosGuestDns.nameservers(listOf("192.168.1.1", "1.0.0.1")),
        )
    }

    @Test
    fun `empty link DNS uses public fallback`() {
        assertEquals(
            CosConstants.GUEST_NAMESERVERS,
            CosGuestDns.nameservers(emptyList()),
        )
    }

    @Test
    fun `blank entries stripped and deduped`() {
        assertEquals(
            listOf("8.8.8.8", "1.1.1.1"),
            CosGuestDns.nameservers(listOf(" 8.8.8.8 ", "", "8.8.8.8", "1.1.1.1")),
        )
    }

    @Test
    fun `whitespace-only link list falls back`() {
        val got = CosGuestDns.nameservers(listOf("  ", "\t"))
        assertEquals(CosConstants.GUEST_NAMESERVERS, got)
        assertTrue(got.isNotEmpty())
    }
}
