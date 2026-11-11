package com.xdutsuay.chidori.computer.api

import com.xdutsuay.chidori.tools.FakeDesktopBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Spec tests for [WifiApiShare]: HTTPS-only, pairing-gated Wi-Fi sessions on 127.0.0.1 with
 * port 0. Lease and idle timers run on the runTest virtual clock via [TestScope.backgroundScope];
 * real TLS HTTP calls are wall-clock, so tests advance virtual time around (not across) them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiApiShareTest {

    private val bridge = FakeDesktopBridge()
    private val identity = TlsIdentity.generate()
    private val factory = TestTls.trustingFactory(identity.toPem())

    private fun TestScope.share(
        wifiAddress: String? = "127.0.0.1",
        control: ComputerControl? = null,
        maxMs: Long = WifiApiShare.DEFAULT_MAX_MS,
        idleMs: Long = WifiApiShare.DEFAULT_IDLE_MS,
    ): WifiApiShare = WifiApiShare(
        scope = backgroundScope,
        bridge = { bridge },
        identity = { identity },
        wifiAddress = { wifiAddress },
        port = 0,
        nowMs = { testScheduler.currentTime },
        control = control,
        maxMs = maxMs,
        idleMs = idleMs,
    )

    /** Pairs over the real TLS endpoint using the current pairing URI; returns the token. */
    private fun TestScope.pairAndGetToken(s: WifiApiShare): Pair<WifiEndpoint, String> {
        val ep = (s.state.value as WifiShareState.Shared).endpoint
        val fields = PairingUri.parse(s.pairingUri()!!)!!
        val response = TestTls.exchange(
            factory,
            ep.port,
            TestTls.post(ComputerApiRoutes.PAIR_PATH, null, """{"code":"${fields.code}"}"""),
        )
        assertEquals(200, TestTls.status(response))
        return ep to TestTls.jsonStringField(response.substringAfter("\r\n\r\n"), "token")
    }

    // ---- start failure paths ----

    @Test
    fun `no wifi address is an Error with NOT_ON_WIFI and nothing starts`() = runTest {
        val s = share(wifiAddress = null)
        val r = s.start()
        assertTrue(r.isFailure)
        val err = s.state.value
        assertTrue("expected Error, was $err", err is WifiShareState.Error)
        assertEquals(WifiApiShare.NOT_ON_WIFI, (err as WifiShareState.Error).message)
        assertNull("nothing is shared", s.pairingUri())
    }

    @Test
    fun `allowControl with a null control is an Error and fails`() = runTest {
        val s = share(control = null)
        val r = s.start(allowControl = true)
        assertTrue(r.isFailure)
        assertTrue("expected Error, was ${s.state.value}", s.state.value is WifiShareState.Error)
    }

    // ---- start success ----

    @Test
    fun `start shares a TLS endpoint with pin fingerprint lease and a pairing code`() = runTest {
        val s = share(maxMs = 60_000)
        try {
            val ep = s.start().getOrThrow()
            assertEquals("127.0.0.1", ep.host)
            assertTrue("ephemeral port must be > 0, was ${ep.port}", ep.port > 0)
            assertEquals(identity.publicKeyPin, ep.publicKeyPin)
            assertEquals(identity.certificateSha256, ep.certificateSha256)
            assertEquals(testScheduler.currentTime + 60_000, ep.expiresAtMs)
            assertFalse(ep.control)
            assertEquals(WifiShareState.Shared(ep), s.state.value)
            assertNotNull("a pairing code must be issued", s.pairing.code.value)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `pairingUri parses to the endpoint host port pin and current code`() = runTest {
        val s = share()
        try {
            val ep = s.start().getOrThrow()
            val fields = PairingUri.parse(s.pairingUri()!!)!!
            assertEquals(ep.host, fields.host)
            assertEquals(ep.port, fields.port)
            assertEquals(ep.publicKeyPin, fields.pin)
            assertEquals(s.pairing.code.value!!.code, fields.code)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `start while shared returns the same endpoint`() = runTest {
        val s = share()
        try {
            val first = s.start().getOrThrow()
            val second = s.start().getOrThrow()
            assertEquals(first, second)
            assertEquals(WifiShareState.Shared(first), s.state.value)
        } finally {
            s.stop()
        }
    }

    // ---- full flow over real TLS ----

    @Test
    fun `pair with the code over real TLS then read state with the token`() = runTest {
        val s = share()
        try {
            s.start().getOrThrow()
            val (ep, token) = pairAndGetToken(s)

            val state = TestTls.exchange(
                factory,
                ep.port,
                TestTls.get(ComputerApiRoutes.STATE_PATH, token),
            )
            assertEquals(200, TestTls.status(state))
            assertTrue(s.state.value is WifiShareState.Shared)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `an unpaired token cannot read state`() = runTest {
        val s = share()
        try {
            val ep = s.start().getOrThrow()
            val response = TestTls.exchange(
                factory,
                ep.port,
                TestTls.get(ComputerApiRoutes.STATE_PATH, "some-random-token"),
            )
            assertEquals(401, TestTls.status(response))
        } finally {
            s.stop()
        }
    }

    // ---- stop ----

    @Test
    fun `stop turns the share off, revokes clients and takes the endpoint down`() = runTest {
        val s = share()
        try {
            s.start().getOrThrow()
            val (ep, token) = pairAndGetToken(s)
            assertEquals(1, s.pairing.clients.value.size)

            s.stop()
            runCurrent()
            assertEquals(WifiShareState.Off, s.state.value)
            assertTrue("stop must revokeAll", s.pairing.clients.value.isEmpty())
            assertNull(s.pairingUri())
            assertThrows("the old token must fail because the server is gone", IOException::class.java) {
                TestTls.exchange(factory, ep.port, TestTls.get(ComputerApiRoutes.STATE_PATH, token))
            }
        } finally {
            s.stop()
        }
    }

    @Test
    fun `stop while off is a no-op`() = runTest {
        val s = share()
        s.stop()
        assertEquals(WifiShareState.Off, s.state.value)
        assertNull(s.pairingUri())
    }

    // ---- leases ----

    @Test
    fun `maxMs expiry turns the share off`() = runTest {
        val s = share(maxMs = 1_000, idleMs = 1_000_000)
        try {
            s.start().getOrThrow()
            advanceTimeBy(999)
            runCurrent()
            assertTrue("must not expire early", s.state.value is WifiShareState.Shared)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(WifiShareState.Off, s.state.value)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `idleMs without a 2xx response turns the share off`() = runTest {
        val s = share(maxMs = 1_000_000, idleMs = 1_000)
        try {
            s.start().getOrThrow()
            advanceTimeBy(999)
            runCurrent()
            assertTrue("must not idle out early", s.state.value is WifiShareState.Shared)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(WifiShareState.Off, s.state.value)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `a 2xx response resets the idle timer`() = runTest {
        val s = share(maxMs = 1_000_000, idleMs = 1_000)
        try {
            val startedAt = testScheduler.currentTime
            s.start().getOrThrow()
            advanceTimeBy(999) // now = start + 999, idle fires at start + 1000
            runCurrent()

            // a successful pairing is a 2xx and must push the idle deadline out by idleMs
            val (_, _) = pairAndGetToken(s)
            Thread.sleep(200) // the server's onResponse runs on a worker thread (wall clock)
            runCurrent()

            advanceTimeBy(999) // now = start + 1998; reset deadline is start + 1999
            runCurrent()
            assertTrue("the 2xx must have reset the idle timer", s.state.value is WifiShareState.Shared)

            advanceTimeBy(2) // now = start + 2000
            runCurrent()
            assertEquals(WifiShareState.Off, s.state.value)
            assertEquals(startedAt, 0L) // sanity: no virtual time slipped before start
        } finally {
            s.stop()
        }
    }

    @Test
    fun `non-2xx responses do not reset the idle timer`() = runTest {
        val s = share(maxMs = 1_000_000, idleMs = 1_000)
        try {
            s.start().getOrThrow()
            val ep = (s.state.value as WifiShareState.Shared).endpoint
            advanceTimeBy(999)
            runCurrent()

            val response = TestTls.exchange(
                factory,
                ep.port,
                TestTls.get(ComputerApiRoutes.STATE_PATH, null), // no token -> 401
            )
            assertEquals(401, TestTls.status(response))
            Thread.sleep(200)
            runCurrent()

            advanceTimeBy(2) // idle deadline was start + 1000, the 401 must not move it
            runCurrent()
            assertEquals("a 401 must not reset the idle timer", WifiShareState.Off, s.state.value)
        } finally {
            s.stop()
        }
    }
}
