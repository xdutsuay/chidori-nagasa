package com.druk.lmplayground.computer.desktop.input

import com.druk.lmplayground.computer.desktop.DesktopGeometry

/** X pointer buttons as xdotool numbers them. */
enum class MouseButton(val x: Int) { LEFT(1), MIDDLE(2), RIGHT(3) }

/**
 * One input action on the COS desktop, in desktop pixels. [toArgv] gives the
 * xdotool argv (without the leading `xdotool`); coordinates are clamped to the
 * screen so a stale mapping can never address pixels outside it.
 */
sealed interface InputCommand {
    fun toArgv(geometry: DesktopGeometry): List<String>

    /** Pointer motion; consecutive moves may be coalesced (only the last one matters). */
    data class MouseMove(val x: Int, val y: Int) : InputCommand {
        override fun toArgv(geometry: DesktopGeometry) = listOf("mousemove") + clamp(x, y, geometry)
    }

    data class MouseDown(val button: MouseButton = MouseButton.LEFT) : InputCommand {
        override fun toArgv(geometry: DesktopGeometry) = listOf("mousedown", button.x.toString())
    }

    data class MouseUp(val button: MouseButton = MouseButton.LEFT) : InputCommand {
        override fun toArgv(geometry: DesktopGeometry) = listOf("mouseup", button.x.toString())
    }

    /** Move to (x, y) and click [count] times (2 = double click). */
    data class Click(val x: Int, val y: Int, val button: MouseButton = MouseButton.LEFT, val count: Int = 1) : InputCommand {
        init { require(count in 1..3) { "count must be 1..3" } }
        override fun toArgv(geometry: DesktopGeometry) = buildList {
            add("mousemove"); addAll(clamp(x, y, geometry)); add("click")
            if (count > 1) { add("--repeat"); add(count.toString()) }
            add(button.x.toString())
        }
    }

    /**
     * Wheel clicks at the current pointer: [dy] > 0 scrolls down (button 5), < 0 up (4);
     * [dx] > 0 right (7), < 0 left (6). Each axis is capped at [MAX_WHEEL_CLICKS].
     */
    data class Scroll(val dx: Int, val dy: Int) : InputCommand {
        override fun toArgv(geometry: DesktopGeometry) = buildList {
            fun axis(n: Int, neg: Int, pos: Int) {
                if (n == 0) return
                add("click"); add("--repeat"); add(minOf(kotlin.math.abs(n), MAX_WHEEL_CLICKS).toString())
                add((if (n > 0) pos else neg).toString())
            }
            axis(dy, 4, 5)
            axis(dx, 6, 7)
        }
    }

    /** `xdotool key` with validated combos (see [DesktopKeys.validCombo]). */
    data class Key(val combos: List<String>) : InputCommand {
        init {
            require(combos.isNotEmpty() && combos.all { DesktopKeys.validCombo(it) }) { "invalid key combo: $combos" }
        }
        override fun toArgv(geometry: DesktopGeometry) = listOf("key", "--") + combos
    }

    /** Literal text (no control characters other than \n and \t). */
    data class Type(val text: String, val delayMs: Int = TYPE_DELAY_MS) : InputCommand {
        init {
            require(text.isNotEmpty()) { "empty text" }
            require(text.none { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t') }) { "control characters" }
        }
        override fun toArgv(geometry: DesktopGeometry) = listOf("type", "--delay", delayMs.toString(), "--", text)
    }

    companion object {
        const val TYPE_DELAY_MS = 12
        const val MAX_WHEEL_CLICKS = 20

        internal fun clamp(x: Int, y: Int, g: DesktopGeometry): List<String> = listOf(
            x.coerceIn(0, (g.width - 1).coerceAtLeast(0)).toString(),
            y.coerceIn(0, (g.height - 1).coerceAtLeast(0)).toString(),
        )
    }
}
