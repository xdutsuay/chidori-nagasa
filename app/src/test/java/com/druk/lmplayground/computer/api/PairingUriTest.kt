package com.druk.lmplayground.computer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec tests for [PairingUri]: exact `chidori-computer://pair?...` format, URL-encoding,
 * parse(build(x)) == x, and rejections (wrong scheme/host, missing or blank fields, bad ports).
 */
class PairingUriTest {

    @Test
    fun `build produces the exact pair URI`() {
        assertEquals(
            "chidori-computer://pair?host=192.168.1.5&port=7902&pin=abcdef&code=123456",
            PairingUri.build(PairingUri.Fields("192.168.1.5", 7902, "abcdef", "123456")),
        )
    }

    @Test
    fun `build URL-encodes slash plus and equals in the pin`() {
        assertEquals(
            "chidori-computer://pair?host=h&port=7902&pin=a%2Fb%2Bc%3Dd&code=123456",
            PairingUri.build(PairingUri.Fields("h", 7902, "a/b+c=d", "123456")),
        )
    }

    @Test
    fun `parse reverses build`() {
        val fields = PairingUri.Fields("192.168.1.5", 7902, "a/b+c=d", "123456")
        assertEquals(fields, PairingUri.parse(PairingUri.build(fields)))
    }

    @Test
    fun `parse accepts the full port range 1 to 65535`() {
        val low = PairingUri.parse("chidori-computer://pair?host=h&port=1&pin=p&code=1")!!
        assertEquals(1, low.port)
        val high = PairingUri.parse("chidori-computer://pair?host=h&port=65535&pin=p&code=1")!!
        assertEquals(65535, high.port)
    }

    @Test
    fun `parse rejects other schemes and hosts`() {
        assertNull(PairingUri.parse("https://pair?host=h&port=7902&pin=p&code=1"))
        assertNull(PairingUri.parse("chidori-computer://other?host=h&port=7902&pin=p&code=1"))
    }

    @Test
    fun `parse rejects missing or blank fields`() {
        assertNull(PairingUri.parse("chidori-computer://pair?port=7902&pin=p&code=1")) // no host
        assertNull(PairingUri.parse("chidori-computer://pair?host=h&pin=p&code=1")) // no port
        assertNull(PairingUri.parse("chidori-computer://pair?host=h&port=7902&code=1")) // no pin
        assertNull(PairingUri.parse("chidori-computer://pair?host=h&port=7902&pin=p")) // no code
        assertNull(PairingUri.parse("chidori-computer://pair?host=&port=7902&pin=p&code=1")) // blank host
        assertNull(PairingUri.parse("chidori-computer://pair?host=h&port=7902&pin=&code=1")) // blank pin
        assertNull(PairingUri.parse("chidori-computer://pair?host=h&port=7902&pin=p&code=")) // blank code
    }

    @Test
    fun `parse rejects ports outside 1 to 65535 and non-numeric ports`() {
        for (port in listOf("0", "65536", "-1", "abc", "123456")) {
            assertNull(
                "port '$port' must be rejected",
                PairingUri.parse("chidori-computer://pair?host=h&port=$port&pin=p&code=1"),
            )
        }
    }
}
