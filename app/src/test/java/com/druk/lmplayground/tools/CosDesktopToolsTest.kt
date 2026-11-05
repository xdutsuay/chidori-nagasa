package com.druk.lmplayground.tools

import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.computer.CosAgentBridge
import com.druk.lmplayground.computer.desktop.ImageFormat
import com.druk.lmplayground.conversation.StoppedToolRound
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CosDesktopToolsTest {
    private var computerOn = true
    private val bridge = FakeDesktopBridge()
    private var published: FakeDesktopBridge? = bridge
    private val desktop = DesktopToolContext({ computerOn }, { published })
    private val images = ToolImageAttachments()
    private val screenshot = CosScreenshotTool(desktop, images)
    private val gui = CosGuiTool(desktop)

    private fun json(s: String) = JSONObject(s)

    // ---- schemas ----

    @Test
    fun `schemas are valid JSON objects with the documented properties`() {
        val shot = json(screenshot.parametersSchema)
        assertEquals("object", shot.getString("type"))
        assertEquals(1280, shot.getJSONObject("properties").getJSONObject("max_edge").getInt("maximum"))
        assertEquals(0, shot.getJSONArray("required").length())

        val g = json(gui.parametersSchema)
        val props = g.getJSONObject("properties")
        assertEquals(listOf("action"), (0 until g.getJSONArray("required").length()).map { g.getJSONArray("required").getString(it) })
        val actions = props.getJSONObject("action").getJSONArray("enum")
        assertEquals(CosGuiArgs.ACTIONS, (0 until actions.length()).map { actions.getString(it) })
        for (p in listOf("x", "y", "button", "double", "text", "keys", "direction", "amount", "dx", "dy", "space")) {
            assertTrue("schema has $p", props.has(p))
        }
        assertEquals(CosGuiArgs.MAX_TEXT_CHARS, props.getJSONObject("text").getInt("maxLength"))
        assertEquals("cos_screenshot", screenshot.name)
        assertEquals("cos_gui", gui.name)
    }

    // ---- gating ----

    @Test
    fun `Computer off - cos_exec-shaped disabled error, bridge untouched`() {
        computerOn = false
        assertFalse(screenshot.isAvailable())
        assertFalse(gui.isAvailable())
        for ((tool, args) in listOf(screenshot to "{}", gui to """{"action":"click","x":1,"y":1}""")) {
            val r = json(tool.execute(args))
            assertEquals(127, r.getInt("exit_code"))
            assertEquals("", r.getString("stdout"))
            assertEquals(CosDesktopFormatter.COS_DISABLED_STDERR, r.getString("stderr"))
            assertTrue(r.getString("error").startsWith("Computer (COS) is disabled"))
            assertTrue(r.getString("error").contains(tool.name))
            // Same key set as cos_exec's disabled result.
            val exec = json(CosExecFormatter.formatResult(ComputerExecResult(127, "", CosDesktopFormatter.COS_DISABLED_STDERR)))
            assertEquals(exec.keys().asSequence().toSet(), r.keys().asSequence().toSet())
        }
        assertTrue(bridge.commands.isEmpty())
        assertTrue(bridge.screenshots.isEmpty())
    }

    @Test
    fun `desktop not running or no bridge - unavailable and explicit error`() {
        bridge.stopDesktop()
        assertFalse(screenshot.isAvailable())
        assertFalse(gui.isAvailable())
        val r = json(gui.execute("""{"action":"click","x":1,"y":1}"""))
        assertEquals(1, r.getInt("exit_code"))
        assertFalse(r.getBoolean("desktop_running"))
        assertTrue(r.getString("error").contains("not running"))
        published = null
        assertFalse(screenshot.isAvailable())
        assertFalse(json(screenshot.execute("{}")).getBoolean("desktop_running"))
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun `registry advertises desktop tools only while the desktop runs`() {
        val registry = ToolRegistry().apply {
            register(screenshot); register(gui)
            setToolEnabled(CosToolDefaults.COS_SCREENSHOT, true)
            setToolEnabled(CosToolDefaults.COS_GUI, true)
        }
        assertTrue(registry.hasEnabledTools())
        assertTrue(registry.isToolActive(CosToolDefaults.COS_GUI))
        assertTrue(registry.toOpenAIToolsJson().contains("cos_screenshot"))
        bridge.stopDesktop()
        assertFalse(registry.hasEnabledTools())
        assertFalse(registry.isToolActive(CosToolDefaults.COS_GUI))
        assertEquals("[]", registry.toOpenAIToolsJson())
        // Still enabled as a preference; a stray call gets the explicit error, not a run.
        assertTrue(registry.isToolEnabled(CosToolDefaults.COS_GUI))
        val res = JSONArray(registry.executeToolCalls(
            JSONArray().put(JSONObject().put("id", "1").put("name", "cos_gui").put("arguments", """{"action":"move","x":1,"y":1}""")).toString(),
        ))
        assertTrue(res.getJSONObject(0).getString("content").contains("not running"))
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun `registry disabled toggle wins even while the desktop runs`() {
        val registry = ToolRegistry().apply { register(gui); setToolEnabled(CosToolDefaults.COS_GUI, false) }
        assertFalse(registry.toOpenAIToolsJson().contains("cos_gui"))
        val res = JSONArray(registry.executeToolCalls(
            JSONArray().put(JSONObject().put("id", "1").put("name", "cos_gui").put("arguments", """{"action":"move","x":1,"y":1}""")).toString(),
        ))
        assertTrue(res.getJSONObject(0).getString("content").contains("cos_gui is disabled"))
        assertTrue(bridge.commands.isEmpty())
    }

    // ---- cos_gui ----

    @Test
    fun `gui runs a fully quoted xdotool command through execInDesktop`() {
        val r = json(gui.execute("""{"action":"click","x":640,"y":360,"double":true}"""))
        assertEquals(0, r.getInt("exit_code"))
        assertEquals("click", r.getString("action"))
        assertEquals(640, r.getInt("screen_x"))
        assertEquals(360, r.getInt("screen_y"))
        assertEquals(listOf("'xdotool' 'mousemove' '640' '360' 'click' '--repeat' '2' '1'"), bridge.commands)
        // DISPLAY is never hard-coded by the tool: the bridge sets it from its state.
        assertFalse(bridge.commands.single().contains("DISPLAY"))
    }

    @Test
    fun `gui type cannot inject shell syntax`() {
        val text = "hi'; touch /tmp/pwned; echo '\$(id)`id`"
        gui.execute(JSONObject().put("action", "type").put("text", text).toString())
        val cmd = bridge.commands.single()
        assertEquals("'xdotool' 'type' '--delay' '12' '--' 'hi'\\''; touch /tmp/pwned; echo '\\''\$(id)`id`'", cmd)
    }

    @Test
    fun `gui validates before touching the desktop`() {
        val r = json(gui.execute("""{"action":"click","x":5000,"y":1}"""))
        assertEquals(2, r.getInt("exit_code"))
        assertTrue(r.getString("error").contains("outside the screen 1280x720"))
        assertEquals(2, json(gui.execute("""{"action":"key","keys":"exec"}""")).getInt("exit_code"))
        assertTrue(bridge.commands.isEmpty())
    }

    @Test
    fun `gui reports xdotool failures and missing xdotool`() {
        bridge.execResult = ComputerExecResult(127, "", "sh: xdotool: not found")
        val r = json(gui.execute("""{"action":"key","keys":"Return"}"""))
        assertEquals(127, r.getInt("exit_code"))
        assertTrue(r.getString("error").contains("apk add xdotool"))
        bridge.execResult = ComputerExecResult(127, "", CosDesktopFormatter.COS_DISABLED_STDERR)
        assertTrue(json(gui.execute("""{"action":"key","keys":"Return"}""")).getString("error").startsWith("Computer (COS) is disabled"))
    }

    @Test
    fun `gui uses the bridge geometry, not a fixed screen`() {
        val big = FakeDesktopBridge(1920, 1080)
        val tool = CosGuiTool(DesktopToolContext({ true }, { big }))
        assertEquals(0, json(tool.execute("""{"action":"move","x":1900,"y":1000}""")).getInt("exit_code"))
        assertEquals(2, json(gui.execute("""{"action":"move","x":1900,"y":1000}""")).getInt("exit_code"))
    }

    // ---- cos_screenshot ----

    @Test
    fun `screenshot at native size stores the image and reports geometry`() {
        val r = json(screenshot.execute("{}"))
        assertEquals(0, r.getInt("exit_code"))
        assertEquals(1280, r.getInt("width"))
        assertEquals(720, r.getInt("height"))
        assertEquals(1280, r.getInt("image_width"))
        assertEquals(1.0, r.getDouble("scale"), 0.0)
        assertEquals("png", r.getString("format"))
        assertNotNull(images.get(r.getString("image_id")))
        assertEquals(listOf(ImageFormat.PNG to 1f), bridge.screenshots)
        assertEquals(1.0, desktop.lastScreenshotScale!!, 0.0)
    }

    @Test
    fun `screenshot downscales large screens to max edge and gui accepts screenshot space`() {
        val big = FakeDesktopBridge(1920, 1080)
        val ctx = DesktopToolContext({ true }, { big })
        val r = json(CosScreenshotTool(ctx, images).execute("{}"))
        assertEquals(1920, r.getInt("width"))
        assertEquals(1280, r.getInt("image_width"))
        assertEquals(720, r.getInt("image_height"))
        assertEquals(1.5, r.getDouble("scale"), 1e-9)
        assertEquals(1f / 1.5f, big.screenshots.single().second, 1e-6f)
        CosGuiTool(ctx).execute("""{"action":"click","x":100,"y":100,"space":"screenshot"}""")
        assertEquals("'xdotool' 'mousemove' '150' '150' 'click' '1'", big.commands.single())

        val small = json(CosScreenshotTool(ctx, images).execute("""{"max_edge":640}"""))
        assertEquals(640, small.getInt("image_width"))
        assertEquals(3.0, small.getDouble("scale"), 1e-9)
    }

    @Test
    fun `screenshot rejects bad max_edge and falls back to jpeg when large`() {
        assertEquals(2, json(screenshot.execute("""{"max_edge":100}""")).getInt("exit_code"))
        assertEquals(2, json(screenshot.execute("""{"max_edge":"big"}""")).getInt("exit_code"))
        bridge.pngOverride = { f, s ->
            if (f == ImageFormat.PNG) FakeDesktopBridge.pngHeader(1280, 720, CosScreenshotTool.MAX_INLINE_IMAGE_BYTES + 1)
            else ByteArray(1000)
        }
        val r = json(screenshot.execute("{}"))
        assertEquals("jpeg", r.getString("format"))
        assertEquals(1280, r.getInt("image_width")) // from the fit, JPEG size isn't parsed
        assertEquals(listOf(ImageFormat.PNG, ImageFormat.JPEG), bridge.screenshots.map { it.first })
    }

    @Test
    fun `screenshot capture failure is an error, not exit 0`() {
        bridge.pngOverride = { _, _ -> throw IllegalStateException("fb missing") }
        val r = json(screenshot.execute("{}"))
        assertEquals(1, r.getInt("exit_code"))
        assertTrue(r.getString("error").contains("fb missing"))
    }

    // ---- chat Stop ----

    private fun assertStopCancels(tool: Tool, args: String) {
        bridge.hang = true
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<String> { tool.execute(args) }
            assertTrue("tool reached the bridge", bridge.entered.await(5, TimeUnit.SECONDS))
            tool.cancelInFlight()
            val r = json(future.get(5, TimeUnit.SECONDS))
            assertEquals(130, r.getInt("exit_code"))
            assertTrue(r.getBoolean("cancelled"))
            assertEquals(CosExecFormatter.cancelledJson(), r.toString())
            assertEquals(CosAgentBridge.CHAT_STOP, bridge.cancelledWith?.message)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `chat Stop cancels cos_gui with exit 130 and the chat-stop reason`() =
        assertStopCancels(gui, """{"action":"type","text":"slow"}""")

    @Test
    fun `chat Stop cancels cos_screenshot with exit 130`() = assertStopCancels(screenshot, "{}")

    @Test
    fun `stopped rounds give desktop tools the cos_exec cancelled shape`() {
        for (name in listOf("cos_screenshot", "cos_gui", "cos_exec")) {
            val c = json(StoppedToolRound.cancelledContentFor(name))
            assertEquals(130, c.getInt("exit_code"))
            assertTrue(c.getBoolean("cancelled"))
        }
        assertEquals(ToolRegistry.CANCELLED_ERROR, StoppedToolRound.cancelledContentFor("web_search"))
    }

    // ---- defaults ----

    @Test
    fun `desktop tools follow the cos_exec default rule`() {
        for (name in CosToolDefaults.DESKTOP_TOOLS) {
            assertTrue(CosToolDefaults.isCosTool(name))
            assertTrue(CosToolDefaults.resolve(name, null, null, computerEnabled = true))
            assertFalse(CosToolDefaults.resolve(name, true, true, computerEnabled = false))
            assertFalse(CosToolDefaults.resolve(name, false, null, computerEnabled = true))
            assertFalse(CosToolDefaults.resolve(name, null, false, computerEnabled = true))
            assertTrue(CosToolDefaults.globalDisplay(name, null))
        }
        assertFalse(CosToolDefaults.isCosTool("web_fetch"))
    }
}
