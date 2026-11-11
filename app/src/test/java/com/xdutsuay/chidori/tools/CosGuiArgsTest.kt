package com.xdutsuay.chidori.tools

import com.xdutsuay.chidori.computer.desktop.DesktopGeometry
import com.xdutsuay.chidori.tools.CosGuiArgs.GuiAction
import com.xdutsuay.chidori.tools.CosGuiArgs.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CosGuiArgsTest {
    private val screen = DesktopGeometry(1280, 720)

    private fun ok(json: String): GuiAction = when (val p = CosGuiArgs.parse(json)) {
        is Parsed.Ok -> p.value
        is Parsed.Invalid -> { fail("expected ok for $json: ${p.message}"); error("unreachable") }
    }

    private fun bad(json: String, contains: String = "") {
        when (val p = CosGuiArgs.parse(json)) {
            is Parsed.Ok -> fail("expected invalid for $json, got ${p.value}")
            is Parsed.Invalid -> assertTrue("'${p.message}' should contain '$contains'", p.message.contains(contains))
        }
    }

    private fun argv(json: String, g: DesktopGeometry = screen, scale: Double? = null): List<String> =
        when (val a = CosGuiArgs.toArgv(ok(json), g, scale)) {
            is Parsed.Ok -> a.value
            is Parsed.Invalid -> { fail("expected argv for $json: ${a.message}"); error("unreachable") }
        }

    private fun argvBad(json: String, contains: String, g: DesktopGeometry = screen, scale: Double? = null) {
        when (val a = CosGuiArgs.toArgv(ok(json), g, scale)) {
            is Parsed.Ok -> fail("expected bounds error for $json, got ${a.value}")
            is Parsed.Invalid -> assertTrue(a.message, a.message.contains(contains))
        }
    }

    @Test
    fun `click move and double click argv`() {
        assertEquals(listOf("mousemove", "10", "20", "click", "1"), argv("""{"action":"click","x":10,"y":20}"""))
        assertEquals(
            listOf("mousemove", "1279", "719", "click", "--repeat", "2", "3"),
            argv("""{"action":"click","x":1279,"y":719,"button":"right","double":true}"""),
        )
        assertEquals(listOf("mousemove", "5", "6"), argv("""{"action":"move","x":"5","y":6.0}"""))
        assertEquals(listOf("mousemove", "0", "0", "click", "2"), argv("""{"action":"CLICK","x":0,"y":0,"button":"middle"}"""))
    }

    @Test
    fun `coordinates are bounds-checked against the real screen`() {
        argvBad("""{"action":"click","x":1280,"y":10}""", "outside the screen 1280x720")
        argvBad("""{"action":"move","x":10,"y":720}""", "outside")
        argvBad("""{"action":"click","x":-1,"y":10}""", "outside")
        bad("""{"action":"click","x":1.5,"y":10}""", "integer")
        bad("""{"action":"click","x":"10; reboot","y":10}""", "integer")
        bad("""{"action":"click","x":10}""", "'y' is required")
        bad("""{"action":"click","x":true,"y":1}""", "integer")
        bad("""{"action":"click","x":1,"y":1,"button":"left; id"}""", "button")
        bad("""{"action":"click","x":1,"y":1,"double":"yes"}""", "double")
    }

    @Test
    fun `screenshot space maps through the last screenshot scale`() {
        val big = DesktopGeometry(1920, 1080)
        assertEquals(
            listOf("mousemove", "150", "300", "click", "1"),
            argv("""{"action":"click","x":100,"y":200,"space":"screenshot"}""", big, 1.5),
        )
        argvBad("""{"action":"click","x":1280,"y":0,"space":"screenshot"}""", "outside the last screenshot 1280x720", big, 1.5)
        argvBad("""{"action":"click","x":1,"y":1,"space":"screenshot"}""", "needs a prior cos_screenshot", big, null)
        bad("""{"action":"click","x":1,"y":1,"space":"pixels"}""", "space")
    }

    @Test
    fun `type passes text verbatim after an end-of-options marker`() {
        val text = "echo \$(id); rm -rf / `x` 'q' \"d\" --help\nnext\tline"
        val json = org.json.JSONObject().put("action", "type").put("text", text).toString()
        assertEquals(listOf("type", "--delay", "12", "--", text), argv(json))
        assertEquals(listOf("type", "--delay", "12", "--", "--clearmodifiers"), argv("""{"action":"type","text":"--clearmodifiers"}"""))
    }

    @Test
    fun `type rejects control characters, empty and oversized text`() {
        bad("""{"action":"type","text":"a\u0007b"}""", "control character")
        bad("""{"action":"type","text":"a\u001bb"}""", "control character")
        bad("""{"action":"type","text":"a\u007fb"}""", "control character")
        bad("""{"action":"type","text":"a\u0085b"}""", "control character")
        bad("""{"action":"type","text":""}""", "empty")
        bad("""{"action":"type"}""", "'text' is required")
        val long = org.json.JSONObject().put("action", "type").put("text", "a".repeat(1001)).toString()
        bad(long, "max 1000")
        val max = org.json.JSONObject().put("action", "type").put("text", "a".repeat(1000)).toString()
        ok(max)
    }

    @Test
    fun `keys are normalised and restricted to keysym charset`() {
        assertEquals(listOf("key", "--", "ctrl+l"), argv("""{"action":"key","keys":"ctrl+l"}"""))
        assertEquals(listOf("key", "--", "Return", "Escape", "ctrl+shift+t"), argv("""{"action":"key","keys":"enter esc ctrl+shift+t"}"""))
        assertEquals(listOf("key", "--", "alt+F4", "super"), argv("""{"action":"key","keys":["alt+F4","win"]}"""))
        assertEquals(listOf("key", "--", "BackSpace", "Prior"), argv("""{"action":"key","key":"backspace pgup"}"""))
        bad("""{"action":"key","keys":"ctrl+$(id)"}""", "invalid key")
        bad("""{"action":"key","keys":"a;b"}""", "invalid key")
        bad("""{"action":"key","keys":"ctrl++l"}""", "invalid key")
        bad("""{"action":"key","keys":"a+b+c+d+e+f"}""", "at most 5")
        bad("""{"action":"key","keys":"a b c d e f g h i"}""", "at most 8")
        bad("""{"action":"key","keys":"   "}""", "must not be empty")
        bad("""{"action":"key","keys":"${"x".repeat(33)}"}""", "invalid key")
        bad("""{"action":"key"}""", "'keys' is required")
    }

    @Test
    fun `bare keys equal to xdotool command names cannot chain commands`() {
        // `xdotool key a exec sh` would run sh: command names are never accepted as keys.
        bad("""{"action":"key","keys":"a exec sh"}""", "not a key name")
        bad("""{"action":"key","keys":"EXEC"}""", "not a key name")
        bad("""{"action":"key","keys":["sleep"]}""", "not a key name")
        bad("""{"action":"key","keys":"type"}""", "not a key name")
        bad("""{"action":"key","keys":"windowkill"}""", "not a key name")
        // Inside a combo they are harmless (an argument with '+' never matches a command).
        ok("""{"action":"key","keys":"ctrl+a"}""")
    }

    @Test
    fun `scroll by direction or delta, optionally at a point`() {
        assertEquals(listOf("click", "--repeat", "3", "5"), argv("""{"action":"scroll","direction":"down"}"""))
        assertEquals(
            listOf("mousemove", "100", "200", "click", "--repeat", "7", "4"),
            argv("""{"action":"scroll","direction":"up","amount":7,"x":100,"y":200}"""),
        )
        assertEquals(
            listOf("click", "--repeat", "2", "5", "click", "--repeat", "4", "6"),
            argv("""{"action":"scroll","dx":-4,"dy":2}"""),
        )
        assertEquals(listOf("click", "--repeat", "1", "7"), argv("""{"action":"scroll","direction":"right","amount":1}"""))
        bad("""{"action":"scroll"}""", "needs 'direction'")
        bad("""{"action":"scroll","direction":"sideways"}""", "direction")
        bad("""{"action":"scroll","direction":"up","amount":21}""", "amount")
        bad("""{"action":"scroll","direction":"up","amount":0}""", "amount")
        bad("""{"action":"scroll","dy":21}""", "within")
        bad("""{"action":"scroll","direction":"up","dy":2}""", "not both")
        bad("""{"action":"scroll","direction":"up","x":5}""", "both 'x' and 'y'")
        argvBad("""{"action":"scroll","direction":"up","x":5000,"y":5}""", "outside")
    }

    @Test
    fun `unknown or missing action and non-object arguments are rejected`() {
        bad("""{"action":"exec","command":"sh"}""", "unknown action")
        bad("""{}""", "'action' is required")
        bad("not json", "JSON object")
        bad("""["click"]""", "JSON object")
    }
}
