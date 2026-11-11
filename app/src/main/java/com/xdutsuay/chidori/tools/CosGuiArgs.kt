package com.xdutsuay.chidori.tools

import com.xdutsuay.chidori.computer.desktop.DesktopGeometry
import com.xdutsuay.chidori.computer.desktop.ScreenshotScaling
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict parsing/validation for `cos_gui` arguments and translation into an
 * xdotool argv. Nothing here ever builds a shell string: the result is a list of
 * argv entries handed to the guest process verbatim.
 *
 * Two stages so the pure syntax check runs before any proot is started:
 * 1. [parse]: JSON -> [GuiAction] (types, enums, charset, length caps).
 * 2. [toArgv]: [GuiAction] + real screen geometry -> argv (bounds, coordinate space).
 */
object CosGuiArgs {

    const val MAX_TEXT_CHARS = 1000
    const val MAX_KEY_COMBOS = 8
    const val MAX_KEYS_PER_COMBO = 5
    const val MAX_KEY_TOKEN_CHARS = 32
    const val MAX_SCROLL = 20
    const val TYPE_DELAY_MS = 12

    val ACTIONS = listOf("click", "move", "type", "key", "scroll")
    val BUTTONS = mapOf("left" to 1, "middle" to 2, "right" to 3)
    val SCROLL_BUTTONS = mapOf("up" to 4, "down" to 5, "left" to 6, "right" to 7)

    private val KEY_TOKEN = Regex("^[A-Za-z0-9_]{1,$MAX_KEY_TOKEN_CHARS}$")
    private val INT_STRING = Regex("^-?\\d{1,6}$")

    /**
     * xdotool subcommand names. `xdotool key` stops at an argument equal to one of
     * these and runs it as a chained command (e.g. `exec`), so a bare key combo
     * matching any of them is rejected. Combos containing `+` can never match.
     */
    val XDOTOOL_COMMANDS: Set<String> = setOf(
        "getactivewindow", "getwindowfocus", "getwindowname", "getwindowclassname",
        "getwindowpid", "getwindowgeometry", "getdisplaygeometry", "search",
        "selectwindow", "help", "version", "behave", "behave_screen_edge", "click",
        "getmouselocation", "key", "keydown", "keyup", "mousedown", "mousemove",
        "mousemove_relative", "mouseup", "set_window", "type", "windowactivate",
        "windowfocus", "windowkill", "windowclose", "windowquit", "windowmap",
        "windowminimize", "windowmove", "windowraise", "windowlower", "windowreparent",
        "windowsize", "windowstate", "windowunmap", "set_num_desktops",
        "get_num_desktops", "set_desktop", "get_desktop", "set_desktop_for_window",
        "get_desktop_for_window", "get_desktop_viewport", "set_desktop_viewport",
        "exec", "sleep",
    )

    /** Friendly names -> X keysyms (case-insensitive). Everything else passes as-is. */
    private val KEY_ALIASES = mapOf(
        "enter" to "Return", "return" to "Return", "esc" to "Escape", "escape" to "Escape",
        "backspace" to "BackSpace", "del" to "Delete", "delete" to "Delete",
        "tab" to "Tab", "space" to "space", "pgup" to "Prior", "pageup" to "Prior",
        "pgdn" to "Next", "pagedown" to "Next", "ins" to "Insert", "insert" to "Insert",
        "home" to "Home", "end" to "End", "up" to "Up", "down" to "Down",
        "left" to "Left", "right" to "Right", "win" to "super", "cmd" to "super",
        "meta" to "super", "control" to "ctrl",
    )

    enum class Space { SCREEN, SCREENSHOT }

    sealed class GuiAction {
        abstract val name: String

        data class Click(val x: Int, val y: Int, val button: Int, val double: Boolean, val space: Space) : GuiAction() {
            override val name = "click"
        }
        data class Move(val x: Int, val y: Int, val space: Space) : GuiAction() {
            override val name = "move"
        }
        data class Type(val text: String) : GuiAction() {
            override val name = "type"
        }
        data class Key(val combos: List<String>) : GuiAction() {
            override val name = "key"
        }
        /** [dy] > 0 scrolls down, [dx] > 0 scrolls right (in wheel clicks). */
        data class Scroll(val dx: Int, val dy: Int, val x: Int?, val y: Int?, val space: Space) : GuiAction() {
            override val name = "scroll"
        }
    }

    sealed class Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>()
        data class Invalid(val message: String) : Parsed<Nothing>()
    }

    private class Bad(message: String) : Exception(message)

    fun parse(arguments: String): Parsed<GuiAction> = try {
        val json = runCatching { JSONObject(arguments.ifBlank { "{}" }) }
            .getOrElse { throw Bad("arguments must be a JSON object") }
        val action = (json.opt("action") as? String)?.trim()?.lowercase()
            ?: throw Bad("'action' is required: one of ${ACTIONS.joinToString()}")
        Parsed.Ok(
            when (action) {
                "click" -> GuiAction.Click(
                    x = int(json, "x", required = true)!!,
                    y = int(json, "y", required = true)!!,
                    button = button(json),
                    double = bool(json, "double"),
                    space = space(json),
                )
                "move" -> GuiAction.Move(
                    x = int(json, "x", required = true)!!,
                    y = int(json, "y", required = true)!!,
                    space = space(json),
                )
                "type" -> GuiAction.Type(text(json))
                "key" -> GuiAction.Key(keys(json))
                "scroll" -> scroll(json)
                else -> throw Bad("unknown action '$action': use one of ${ACTIONS.joinToString()}")
            },
        )
    } catch (e: Bad) {
        Parsed.Invalid(e.message ?: "invalid arguments")
    }

    /**
     * Builds the xdotool argv (without the `xdotool` program name). Coordinates in
     * [Space.SCREENSHOT] are mapped through [screenshotScale] (from the last
     * `cos_screenshot`); all coordinates must land inside [geometry].
     */
    fun toArgv(action: GuiAction, geometry: DesktopGeometry, screenshotScale: Double?): Parsed<List<String>> = try {
        Parsed.Ok(
            when (action) {
                is GuiAction.Click -> {
                    val (x, y) = point(action.x, action.y, action.space, geometry, screenshotScale)
                    buildList {
                        add("mousemove"); add(x.toString()); add(y.toString())
                        add("click")
                        if (action.double) { add("--repeat"); add("2") }
                        add(action.button.toString())
                    }
                }
                is GuiAction.Move -> {
                    val (x, y) = point(action.x, action.y, action.space, geometry, screenshotScale)
                    listOf("mousemove", x.toString(), y.toString())
                }
                is GuiAction.Type -> listOf("type", "--delay", TYPE_DELAY_MS.toString(), "--", action.text)
                is GuiAction.Key -> listOf("key", "--") + action.combos
                is GuiAction.Scroll -> buildList {
                    if (action.x != null && action.y != null) {
                        val (x, y) = point(action.x, action.y, action.space, geometry, screenshotScale)
                        add("mousemove"); add(x.toString()); add(y.toString())
                    }
                    if (action.dy != 0) {
                        add("click"); add("--repeat"); add(kotlin.math.abs(action.dy).toString())
                        add((if (action.dy > 0) SCROLL_BUTTONS["down"] else SCROLL_BUTTONS["up"]).toString())
                    }
                    if (action.dx != 0) {
                        add("click"); add("--repeat"); add(kotlin.math.abs(action.dx).toString())
                        add((if (action.dx > 0) SCROLL_BUTTONS["right"] else SCROLL_BUTTONS["left"]).toString())
                    }
                }
            },
        )
    } catch (e: Bad) {
        Parsed.Invalid(e.message ?: "invalid arguments")
    }

    /** Whether [action] needs the screen geometry (anything with coordinates). */
    fun needsGeometry(action: GuiAction): Boolean = when (action) {
        is GuiAction.Click, is GuiAction.Move -> true
        is GuiAction.Scroll -> action.x != null
        else -> false
    }

    // ---- helpers ----

    private fun point(x: Int, y: Int, space: Space, g: DesktopGeometry, scale: Double?): Pair<Int, Int> {
        if (space == Space.SCREENSHOT) {
            val s = scale ?: throw Bad("space='screenshot' needs a prior cos_screenshot in this chat")
            val iw = kotlin.math.max(1, kotlin.math.round(g.width / s).toInt())
            val ih = kotlin.math.max(1, kotlin.math.round(g.height / s).toInt())
            if (x !in 0 until iw || y !in 0 until ih) {
                throw Bad("($x,$y) is outside the last screenshot ${iw}x$ih")
            }
            return ScreenshotScaling.toScreen(x, s, g.width) to ScreenshotScaling.toScreen(y, s, g.height)
        }
        if (!g.contains(x, y)) {
            throw Bad("($x,$y) is outside the screen ${g.width}x${g.height}: x must be 0..${g.width - 1}, y 0..${g.height - 1}")
        }
        return x to y
    }

    private fun int(json: JSONObject, key: String, required: Boolean): Int? {
        if (!json.has(key) || json.isNull(key)) {
            if (required) throw Bad("'$key' is required (integer pixels)")
            return null
        }
        return when (val v = json.get(key)) {
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else throw Bad("'$key' out of range")
            is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e6) v.toInt() else throw Bad("'$key' must be an integer")
            is String -> if (INT_STRING.matches(v.trim())) v.trim().toInt() else throw Bad("'$key' must be an integer")
            else -> throw Bad("'$key' must be an integer")
        }
    }

    private fun bool(json: JSONObject, key: String): Boolean {
        if (!json.has(key) || json.isNull(key)) return false
        return when (val v = json.get(key)) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw Bad("'$key' must be true or false")
            }
            else -> throw Bad("'$key' must be true or false")
        }
    }

    private fun button(json: JSONObject): Int {
        if (!json.has("button") || json.isNull("button")) return 1
        return when (val v = json.get("button")) {
            is String -> BUTTONS[v.trim().lowercase()]
                ?: throw Bad("'button' must be left, middle or right")
            is Int -> if (v in 1..3) v else throw Bad("'button' must be left, middle or right")
            else -> throw Bad("'button' must be left, middle or right")
        }
    }

    private fun space(json: JSONObject): Space {
        if (!json.has("space") || json.isNull("space")) return Space.SCREEN
        return when ((json.get("space") as? String)?.trim()?.lowercase()) {
            "screen" -> Space.SCREEN
            "screenshot" -> Space.SCREENSHOT
            else -> throw Bad("'space' must be 'screen' or 'screenshot'")
        }
    }

    private fun text(json: JSONObject): String {
        val v = json.opt("text") as? String ?: throw Bad("'text' is required (string) for action=type")
        if (v.isEmpty()) throw Bad("'text' must not be empty")
        if (v.length > MAX_TEXT_CHARS) throw Bad("'text' is ${v.length} chars; max $MAX_TEXT_CHARS per call")
        v.forEach { c ->
            val bad = (c < ' ' && c != '\n' && c != '\t') || c == '\u007F' || c in '\u0080'..'\u009F'
            if (bad) throw Bad("'text' contains a control character (U+%04X); only \\n and \\t are allowed".format(c.code))
        }
        return v
    }

    private fun keys(json: JSONObject): List<String> {
        val raw = json.opt("keys") ?: json.opt("key")
            ?: throw Bad("'keys' is required for action=key, e.g. \"ctrl+l\" or \"Return\"")
        val combos = when (raw) {
            is String -> raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            is JSONArray -> (0 until raw.length()).map {
                (raw.opt(it) as? String)?.trim() ?: throw Bad("'keys' entries must be strings")
            }
            else -> throw Bad("'keys' must be a string like \"ctrl+shift+t\"")
        }
        if (combos.isEmpty()) throw Bad("'keys' must not be empty")
        if (combos.size > MAX_KEY_COMBOS) throw Bad("at most $MAX_KEY_COMBOS key combos per call")
        return combos.map { normalizeCombo(it) }
    }

    private fun normalizeCombo(combo: String): String {
        val tokens = combo.split('+')
        if (tokens.size > MAX_KEYS_PER_COMBO) throw Bad("'$combo': at most $MAX_KEYS_PER_COMBO keys per combo")
        val out = tokens.map { t ->
            if (!KEY_TOKEN.matches(t)) {
                throw Bad("invalid key '$t' in '$combo': keys are X keysym names [A-Za-z0-9_], joined with '+'")
            }
            KEY_ALIASES[t.lowercase()] ?: t
        }
        val joined = out.joinToString("+")
        if (out.size == 1 && joined.lowercase() in XDOTOOL_COMMANDS) {
            throw Bad("'$combo' is not a key name")
        }
        return joined
    }

    private fun scroll(json: JSONObject): GuiAction.Scroll {
        val direction = (json.opt("direction") as? String)?.trim()?.lowercase()
        var dx = 0
        var dy = 0
        if (direction != null) {
            val amount = int(json, "amount", required = false) ?: 3
            if (amount !in 1..MAX_SCROLL) throw Bad("'amount' must be 1..$MAX_SCROLL")
            when (direction) {
                "up" -> dy = -amount
                "down" -> dy = amount
                "left" -> dx = -amount
                "right" -> dx = amount
                else -> throw Bad("'direction' must be up, down, left or right")
            }
            if (json.has("dx") || json.has("dy")) throw Bad("use either 'direction'+'amount' or 'dx'/'dy', not both")
        } else {
            dx = int(json, "dx", required = false) ?: 0
            dy = int(json, "dy", required = false) ?: 0
            if (dx == 0 && dy == 0) throw Bad("scroll needs 'direction' (+'amount') or non-zero 'dx'/'dy'")
            if (dx !in -MAX_SCROLL..MAX_SCROLL || dy !in -MAX_SCROLL..MAX_SCROLL) {
                throw Bad("'dx'/'dy' must be within -$MAX_SCROLL..$MAX_SCROLL wheel clicks")
            }
        }
        val x = int(json, "x", required = false)
        val y = int(json, "y", required = false)
        if ((x == null) != (y == null)) throw Bad("give both 'x' and 'y' to scroll at a point, or neither")
        return GuiAction.Scroll(dx, dy, x, y, space(json))
    }
}
