package com.xdutsuay.chidori.computer.api

import com.xdutsuay.chidori.computer.desktop.ShareLease
import com.xdutsuay.chidori.tools.FakeDesktopBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Spec tests for [ComputerApiShare]: one loopback Computer API session over a real
 * [ComputerApiServer] started with port 0 (ephemeral — never the fixed default port).
 * The lease wait runs on the runTest virtual clock via [TestScope.backgroundScope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComputerApiShareTest {

    private val bridge = FakeDesktopBridge()

    private fun TestScope.share(tokens: Iterator<String>): ComputerApiShare =
        ComputerApiShare(
            scope = backgroundScope,
            bridge = { bridge },
            port = 0,
            token = tokens::next,
            nowMs = { testScheduler.currentTime },
        )

    /** Real HTTP POST against the shared server; returns status and body text. */
    private fun post(port: Int, token: String, target: String, body: String): Pair<Int, String> {
        Socket().use { s ->
            s.connect(InetSocketAddress(ComputerApiServer.LOOPBACK, port), 2_000)
            s.soTimeout = 5_000
            val head = "POST $target HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Authorization: Bearer $token\r\n\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().write(body.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            val text = String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
            val headEnd = text.indexOf("\r\n\r\n")
            val status = text.substringBefore("\r\n").substringAfter(' ').substringBefore(' ').toInt()
            return status to (if (headEnd >= 0) text.substring(headEnd + 4) else "")
        }
    }

    /**
     * After a stop/expiry the old endpoint must be unusable: either the connection is
     * refused outright, or the server answers with something other than 200 for the
     * old token (never a served state).
     */
    private fun oldTokenRejected(port: Int, token: String) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ComputerApiServer.LOOPBACK, port), 2_000)
                s.soTimeout = 2_000
                s.getOutputStream().write(
                    ("GET /v1/computer/state HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                        "Authorization: Bearer $token\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                s.getOutputStream().flush()
                val bytes = try {
                    s.getInputStream().readBytes()
                } catch (_: IOException) {
                    return // connection cut without a response: rejected
                }
                val text = String(bytes, Charsets.ISO_8859_1)
                val status = text.substringBefore("\r\n").substringAfter(' ').substringBefore(' ').toIntOrNull()
                assertTrue("old token must not be served, got status $status ($text)", status == null || status != 200)
            }
        } catch (_: IOException) {
            // connection refused: the server is gone, which is also rejection
        }
    }

    // ---- start ----

    @Test
    fun `start shares a loopback endpoint with the injected token and lease expiry`() = runTest {
        val s = share(ArrayDeque(listOf("tok-1")).iterator())
        try {
            val ep = s.start(ShareLease(maxMs = 5_000)).getOrThrow()
            assertEquals("127.0.0.1", ep.host)
            assertTrue("ephemeral port must be > 0, was ${ep.port}", ep.port > 0)
            assertEquals("tok-1", ep.token)
            assertEquals(testScheduler.currentTime + 5_000, ep.expiresAtMs)
            assertEquals(ApiShareState.Shared(ep), s.state.value)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `start while shared returns the same endpoint and token`() = runTest {
        val s = share(ArrayDeque(listOf("tok-1", "unused")).iterator())
        try {
            val first = s.start(ShareLease(maxMs = 5_000)).getOrThrow()
            val second = s.start(ShareLease(maxMs = 5_000)).getOrThrow()
            assertEquals(first, second)
            assertEquals(ApiShareState.Shared(first), s.state.value)
        } finally {
            s.stop()
        }
    }

    // ---- stop ----

    @Test
    fun `stop turns the share off and the old token is no longer served`() = runTest {
        val s = share(ArrayDeque(listOf("tok-1")).iterator())
        val ep = s.start(ShareLease(maxMs = 60_000)).getOrThrow()
        try {
            s.stop()
            runCurrent()
            assertEquals(ApiShareState.Off, s.state.value)
            oldTokenRejected(ep.port, ep.token)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `stop is a no-op while off`() = runTest {
        val s = share(ArrayDeque(listOf("tok-1")).iterator())
        s.stop()
        assertEquals(ApiShareState.Off, s.state.value)
    }

    @Test
    fun `the next start after a stop uses a fresh token`() = runTest {
        val s = share(ArrayDeque(listOf("tok-a", "tok-b")).iterator())
        try {
            val first = s.start(ShareLease(maxMs = 60_000)).getOrThrow()
            s.stop()
            runCurrent()

            val second = s.start(ShareLease(maxMs = 60_000)).getOrThrow()
            assertEquals("tok-b", second.token)
            assertNotEquals(first.token, second.token)
            assertTrue(second.port > 0)
            assertEquals(ApiShareState.Shared(second), s.state.value)
        } finally {
            s.stop()
        }
    }

    // ---- lease ----

    @Test
    fun `lease expiry turns the share off and stops the server`() = runTest {
        val s = share(ArrayDeque(listOf("tok-1")).iterator())
        try {
            val ep = s.start(ShareLease(maxMs = 1_000)).getOrThrow()
            assertEquals(testScheduler.currentTime + 1_000, ep.expiresAtMs)

            advanceTimeBy(999)
            runCurrent()
            assertTrue("lease must not fire early, was ${s.state.value}", s.state.value is ApiShareState.Shared)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(ApiShareState.Off, s.state.value)
            oldTokenRejected(ep.port, ep.token)
        } finally {
            s.stop()
        }
    }

    @Test
    fun `a manual stop cancels the pending lease`() = runTest {
        val s = share(ArrayDeque(listOf("tok-1")).iterator())
        try {
            s.start(ShareLease(maxMs = 1_000)).getOrThrow()
            s.stop()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("lease must not fire after a manual stop", ApiShareState.Off, s.state.value)
        } finally {
            s.stop()
        }
    }

    // ---- randomToken ----

    @Test
    fun `randomToken is 64 lowercase hex characters and calls differ`() {
        val seen = HashSet<String>()
        repeat(200) {
            val t = ComputerApiShare.randomToken()
            assertEquals(64, t.length)
            t.forEach { c ->
                assertTrue("bad character '$c'", c in '0'..'9' || c in 'a'..'f')
            }
            seen += t
        }
        assertEquals("tokens must be unique across calls", 200, seen.size)
    }

    // ---- slice 2: control sharing ----

    @Test
    fun `allowControl=true sets endpoint control and exec is reachable over real HTTP`() = runTest {
        val ctl = FakeComputerControl(execResultJson = """{"exit":0}""")
        val s = ComputerApiShare(
            backgroundScope, { bridge }, port = 0,
            token = { "tok-1" }, nowMs = { testScheduler.currentTime }, control = ctl,
        )
        try {
            val ep = s.start(ShareLease(maxMs = 60_000), allowControl = true).getOrThrow()
            assertEquals(ComputerApiServer.LOOPBACK, ep.host)
            assertTrue(ep.port > 0)
            assertTrue("endpoint must advertise control", ep.control)
            assertEquals(ApiShareState.Shared(ep), s.state.value)

            val (status, body) = post(ep.port, ep.token, ComputerApiRoutes.EXEC_PATH, """{"command":"echo hi"}""")
            assertEquals(200, status)
            assertEquals("""{"exit":0}""", body)
            assertEquals(
                listOf(FakeComputerControl.ApprovalCall("cos_exec", "echo hi")),
                ctl.approvals,
            )
        } finally {
            s.stop()
        }
    }

    @Test
    fun `allowControl=false leaves exec at 403 and the control is never called`() = runTest {
        val ctl = FakeComputerControl()
        val s = ComputerApiShare(
            backgroundScope, { bridge }, port = 0,
            token = { "tok-1" }, nowMs = { testScheduler.currentTime }, control = ctl,
        )
        try {
            val ep = s.start(ShareLease(maxMs = 60_000), allowControl = false).getOrThrow()
            assertFalse("endpoint must not advertise control", ep.control)

            val (status, _) = post(ep.port, ep.token, ComputerApiRoutes.EXEC_PATH, """{"command":"ls"}""")
            assertEquals(403, status)
            assertTrue(ctl.approvals.isEmpty())
            assertTrue(ctl.execCalls.isEmpty())
        } finally {
            s.stop()
        }
    }

    @Test
    fun `allowControl=true with a null control fails with Error and starts nothing`() = runTest {
        val s = ComputerApiShare(
            backgroundScope, { bridge }, port = 0,
            token = { "tok-1" }, nowMs = { testScheduler.currentTime }, control = null,
        )
        val r = s.start(ShareLease(maxMs = 60_000), allowControl = true)
        assertTrue("expected failure", r.isFailure)
        assertTrue("expected Error state, was ${s.state.value}", s.state.value is ApiShareState.Error)
    }

    @Test
    fun `start while shared ignores allowControl and keeps the endpoint unchanged`() = runTest {
        val ctl = FakeComputerControl()
        val s = ComputerApiShare(
            backgroundScope, { bridge }, port = 0,
            token = { "tok-1" }, nowMs = { testScheduler.currentTime }, control = ctl,
        )
        try {
            val first = s.start(ShareLease(maxMs = 60_000), allowControl = false).getOrThrow()
            val second = s.start(ShareLease(maxMs = 60_000), allowControl = true).getOrThrow()
            assertEquals("shared start must return the same endpoint whatever allowControl", first, second)
            assertFalse(first.control)

            val (status, _) = post(first.port, first.token, ComputerApiRoutes.EXEC_PATH, """{"command":"ls"}""")
            assertEquals("the running session must stay read-only", 403, status)
        } finally {
            s.stop()
        }
    }
}
