package com.druk.lmplayground.tools

import com.druk.lmplayground.computer.CosAgentBridge
import com.druk.lmplayground.computer.desktop.input.StreamingFakeBridge
import com.druk.lmplayground.computer.desktop.input.XdotoolSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** cos_gui through the long-lived [XdotoolSession] (stream-capable bridge). */
class CosGuiSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = StreamingFakeBridge()
    private var computerOn = true
    private val session = XdotoolSession(bridge, scope)
    private val gui = CosGuiTool(DesktopToolContext({ computerOn }, { bridge }), sessionFor = { session })

    @After fun tearDown() = scope.cancel()

    @Test
    fun `click and type go through the session stream, not execInDesktop`() {
        val r = JSONObject(gui.execute("""{"action":"click","x":640,"y":360,"double":true}"""))
        assertEquals(0, r.getInt("exit_code"))
        assertEquals(640, r.getInt("screen_x"))
        val t = JSONObject(gui.execute("""{"action":"type","text":"echo \"hi\"; it's ${'$'}HOME\n"}"""))
        assertEquals(0, t.getInt("exit_code"))
        assertEquals(
            listOf(
                "mousemove 640 360 click --repeat 2 1",
                "type --delay 12 -- 'echo \"hi\"; it' \"'s \$HOME\"", "key Return",
            ),
            bridge.scriptLines,
        )
        assertTrue(bridge.base.commands.isEmpty())
        assertEquals(1, bridge.streams.size)
    }

    @Test
    fun `Computer off is still refused before the session is touched`() {
        computerOn = false
        assertEquals(127, JSONObject(gui.execute("""{"action":"click","x":1,"y":1}""")).getInt("exit_code"))
        assertTrue(bridge.streams.isEmpty())
    }

    @Test
    fun `script-mode stderr is passed through with exit 0`() {
        bridge.onLine = { st, l -> if (l.startsWith("key")) st.stderrLine("(symbol) No such key name 'Hyper_Q'. Ignoring it.") }
        val r = JSONObject(gui.execute("""{"action":"key","keys":"Hyper_Q"}"""))
        assertEquals(0, r.getInt("exit_code"))
        assertTrue(r.getString("stderr").contains("No such key name"))
        assertFalse(r.has("error"))
    }

    @Test
    fun `chat Stop during a session call is exit 130 and kills the stream`() {
        gui.execute("""{"action":"move","x":1,"y":1}""")
        bridge.streams.single().mute = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit<String> { gui.execute("""{"action":"type","text":"very long text"}""") }
            val deadline = System.currentTimeMillis() + 5_000
            while (bridge.streams.single().lines.none { it.startsWith("type") } && System.currentTimeMillis() < deadline) Thread.sleep(5)
            gui.cancelInFlight()
            val r = JSONObject(f.get(5, TimeUnit.SECONDS))
            assertEquals(130, r.getInt("exit_code"))
            assertTrue(r.getBoolean("cancelled"))
            assertEquals(CosExecFormatter.cancelledJson(), r.toString())
            assertEquals(listOf(CosAgentBridge.CHAT_STOP), bridge.streams.single().closeReasons)
        } finally {
            pool.shutdownNow()
        }
        // The next call restarts the stream.
        assertEquals(0, JSONObject(gui.execute("""{"action":"move","x":2,"y":2}""")).getInt("exit_code"))
        assertEquals(2, bridge.streams.size)
    }
}
