package com.druk.lmplayground.computer.desktop.input

import com.druk.lmplayground.computer.CosAgentBridge
import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.tools.FakeDesktopBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XdotoolSessionTest {
    private val bridge = StreamingFakeBridge()

    private fun TestScope.session(
        b: com.druk.lmplayground.computer.desktop.DesktopBridge = bridge,
        config: XdotoolSession.Config = XdotoolSession.Config(),
        scope: CoroutineScope = backgroundScope,
    ) = XdotoolSession(b, scope, config, now = { testScheduler.currentTime })

    /** advanceUntilIdle() ignores backgroundScope work, so advance virtual time instead. */
    private fun TestScope.settle() { runCurrent(); advanceTimeBy(5_000); runCurrent() }

    @Test
    fun `starts lazily in script mode and streams commands`() = runTest {
        val s = session()
        settle()
        assertEquals(0, bridge.streams.size)
        assertEquals(XdotoolSession.Mode.NONE, s.mode)
        assertTrue(s.send(InputCommand.Click(10, 20)))
        assertTrue(s.send(InputCommand.Type("a; b")))
        settle()
        assertEquals("exec xdotool -", bridge.streams.single().cmd)
        assertEquals(XdotoolSession.Mode.SCRIPT, s.mode)
        assertEquals(listOf("getmouselocation", "mousemove 10 20 click 1", "type --delay 12 -- \"a; b\""), bridge.streams.single().lines)
        assertTrue(bridge.base.commands.isEmpty())
    }

    @Test
    fun `run waits for the sync marker and reports stderr`() = runTest {
        bridge.onLine = { st, l -> if (l.startsWith("key")) st.stderrLine("(symbol) No such key name 'Bogus'. Ignoring it.") }
        val s = session()
        val r = s.run(listOf("key", "--", "Bogus"))
        assertEquals(0, r.exitCode)
        assertTrue(r.stderr.contains("No such key name"))
        assertEquals(listOf("getmouselocation", "key -- Bogus", "getmouselocation"), bridge.streams.single().lines)
        assertEquals("", s.run(listOf("mousemove", "1", "1")).stderr)
    }

    @Test
    fun `mousemoves are coalesced while queued`() = runTest {
        val s = session()
        s.send(InputCommand.MouseMove(1, 1)); s.send(InputCommand.MouseMove(2, 2)); s.send(InputCommand.MouseMove(3, 3))
        s.send(InputCommand.MouseDown())
        s.send(InputCommand.MouseMove(4, 4)); s.send(InputCommand.MouseMove(5, 5))
        s.send(InputCommand.MouseUp())
        settle()
        assertEquals(listOf("mousemove 3 3", "mousedown 1", "mousemove 5 5", "mouseup 1"), bridge.scriptLines)
        assertEquals(3, s.droppedMoves)
    }

    @Test
    fun `bounded queue drops moves first, then rejects`() = runTest {
        val s = session(config = XdotoolSession.Config(queueCapacity = 3))
        assertTrue(s.send(InputCommand.MouseMove(1, 1)))
        assertTrue(s.send(InputCommand.Click(1, 1)))
        assertTrue(s.send(InputCommand.Click(2, 2)))
        assertTrue(s.send(InputCommand.Click(3, 3))) // full: the queued move is dropped
        assertFalse(s.send(InputCommand.Click(4, 4))) // full of clicks: rejected
        assertEquals(1, s.rejected)
        settle()
        assertEquals(listOf("mousemove 1 1 click 1", "mousemove 2 2 click 1", "mousemove 3 3 click 1"), bridge.scriptLines)
        assertEquals(0, s.queueSize)
    }

    @Test
    fun `submit suspends until there is room`() = runTest {
        val s = session(config = XdotoolSession.Config(queueCapacity = 1))
        s.send(InputCommand.Click(1, 1))
        val d = async { s.submit(InputCommand.Click(2, 2)) }
        settle()
        assertTrue(d.isCompleted)
        assertEquals(listOf("mousemove 1 1 click 1", "mousemove 2 2 click 1"), bridge.scriptLines)
    }

    @Test
    fun `restarts after the process dies`() = runTest {
        var killed = false
        bridge.onLine = { st, l -> if (l.startsWith("mousemove 1 ") && !killed) { killed = true; st.die(1) } }
        val s = session()
        s.send(InputCommand.Click(1, 1)); settle()
        assertEquals(1, bridge.streams[0].exitCode.value)
        s.send(InputCommand.Click(2, 2)); settle()
        assertEquals(2, bridge.streams.size)
        assertEquals(listOf("getmouselocation", "mousemove 2 2 click 1"), bridge.streams[1].lines)
        assertEquals(XdotoolSession.Mode.SCRIPT, s.mode)
    }

    @Test
    fun `a restart storm degrades to exec until the desktop restarts`() = runTest {
        bridge.onLine = { st, l -> if (l.startsWith("mousemove")) st.die(1) }
        val s = session(config = XdotoolSession.Config(maxRestarts = 3))
        repeat(6) { s.send(InputCommand.Click(it, it)); settle() }
        assertEquals(4, bridge.streams.size) // 3 restarts allowed, the 4th death degrades
        assertEquals(XdotoolSession.Mode.EXEC, s.mode)
        assertEquals(listOf("'xdotool' 'mousemove' '4' '4' 'click' '1'", "'xdotool' 'mousemove' '5' '5' 'click' '1'"), bridge.base.commands)
        // Backoff grew between restarts.
        assertTrue(testScheduler.currentTime >= 100 + 200 + 400)
        // Desktop restarted → streams are tried again.
        bridge.onLine = { _, _ -> }
        bridge.base.stopDesktop(); settle()
        bridge.base.state.value = DesktopState.Running(":7", 1280, 720, 24, "/root/.Xauthority", java.io.File("/x"), null)
        settle()
        s.send(InputCommand.Click(9, 9)); advanceTimeBy(60_000); settle()
        assertEquals(XdotoolSession.Mode.SCRIPT, s.mode)
        assertEquals(5, bridge.streams.size)
    }

    @Test
    fun `closed with the desktop stop and restarted lazily`() = runTest {
        val s = session()
        s.send(InputCommand.Click(1, 1)); settle()
        bridge.base.stopDesktop(); settle()
        assertEquals(listOf("desktop-stop"), bridge.streams[0].closeReasons)
        assertFalse(s.send(InputCommand.Click(2, 2)))
        bridge.base.state.value = DesktopState.Running(":7", 1280, 720, 24, "/root/.Xauthority", java.io.File("/x"), null)
        assertTrue(s.send(InputCommand.Click(3, 3))); settle()
        assertEquals(2, bridge.streams.size)
        s.close(); settle()
        assertEquals(listOf(XdotoolSessionTestCloseReason), bridge.streams[1].closeReasons)
        assertFalse(s.send(InputCommand.Click(4, 4)))
    }

    @Test
    fun `cancelled run kills the stream with the chat-stop reason`() = runTest {
        val s = session()
        s.send(InputCommand.MouseMove(1, 1)); settle()
        bridge.streams[0].mute = true // a long `type` that never reaches the marker
        val call = async { s.run(listOf("type", "--delay", "12", "--", "long text")) }
        runCurrent()
        assertTrue(bridge.streams[0].lines.contains("type --delay 12 -- \"long text\""))
        call.cancel(CancellationException(CosAgentBridge.CHAT_STOP))
        settle()
        assertEquals(listOf(CosAgentBridge.CHAT_STOP), bridge.streams[0].closeReasons)
        assertTrue(call.isCancelled)
        // Next command starts a fresh stream.
        assertEquals(0, s.run(listOf("mousemove", "2", "2")).exitCode)
        assertEquals(2, bridge.streams.size)
    }

    @Test
    fun `a call queued but not started is just dropped on cancel`() = runTest {
        val s = session(config = XdotoolSession.Config(queueCapacity = 4))
        val call = async { s.run(listOf("mousemove", "1", "1")) }
        call.cancel(CancellationException(CosAgentBridge.CHAT_STOP)) // before the writer ran
        settle()
        assertTrue(bridge.scriptLines.isEmpty())
        assertTrue(bridge.streams.all { it.closeReasons.isEmpty() })
    }

    @Test
    fun `sync timeout is exit 124 and closes the stream`() = runTest {
        val s = session(config = XdotoolSession.Config(syncTimeoutMs = 1_000))
        s.send(InputCommand.MouseMove(1, 1)); settle()
        bridge.streams[0].mute = true
        val r = s.run(listOf("mousemove", "2", "2"))
        assertEquals(124, r.exitCode)
        assertEquals(listOf("desktop-input-timeout"), bridge.streams[0].closeReasons)
    }

    @Test
    fun `falls back to a guest shell when script mode does not answer`() = runTest {
        bridge.scriptWorks = false
        val s = session()
        val r = s.run(listOf("type", "--delay", "12", "--", "it's \$HOME"))
        assertEquals(0, r.exitCode)
        assertEquals(XdotoolSession.Mode.SHELL, s.mode)
        val sh = bridge.streams.last()
        assertEquals("exec /bin/sh", sh.cmd)
        assertEquals(
            listOf(
                "command -v xdotool >/dev/null 2>&1; echo \"@@cos-sync \$?\"",
                "'xdotool' 'type' '--delay' '12' '--' 'it'\\''s \$HOME'",
                "echo \"@@cos-sync \$?\"",
            ),
            sh.lines,
        )
        sh.shellExit = 1
        assertEquals(1, s.run(listOf("key", "--", "x")).exitCode)
    }

    @Test
    fun `no xdotool anywhere - exec fallback reports it`() = runTest {
        bridge.scriptWorks = false; bridge.shellHasXdotool = false
        bridge.base.execResult = ComputerExecResult(127, "", "sh: xdotool: not found")
        val s = session()
        assertEquals(127, s.run(listOf("mousemove", "1", "1")).exitCode)
        assertEquals(XdotoolSession.Mode.EXEC, s.mode)
        val opened = bridge.streams.size
        s.run(listOf("mousemove", "2", "2"))
        assertEquals(opened, bridge.streams.size) // no retry storm
    }

    @Test
    fun `bridge without stream support uses execInDesktop`() = runTest {
        val plain = FakeDesktopBridge()
        val s = session(b = plain)
        assertEquals(0, s.run(listOf("mousemove", "5", "6")).exitCode)
        s.send(InputCommand.Click(7, 8)); settle()
        assertEquals(XdotoolSession.Mode.EXEC, s.mode)
        assertEquals(listOf("'xdotool' 'mousemove' '5' '6'", "'xdotool' 'mousemove' '7' '8' 'click' '1'"), plain.commands)
    }

    @Test
    fun `injection - hostile text cannot leave the type command`() = runTest {
        val s = session()
        s.run(InputCommand.Type("x\"; key ctrl+alt+Delete; '\n\$1 #c").toArgv(com.druk.lmplayground.computer.desktop.DesktopGeometry(1, 1)))
        val lines = bridge.scriptLines.filter { it != "getmouselocation" }
        assertEquals(
            listOf("type --delay 12 -- 'x\"; key ctrl+alt+Delete; ' \"'\"", "key Return", "key dollar", "type --delay 12 -- \"1 #c\""),
            lines,
        )
        assertEquals(1, bridge.streams.size)
    }

    private companion object {
        const val XdotoolSessionTestCloseReason = com.druk.lmplayground.computer.desktop.DesktopStream.CLOSE_REASON
    }
}
