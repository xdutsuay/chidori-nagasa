package com.druk.lmplayground.computer.desktop.input

/** Keys of the Desktop tab's keyboard bar and their X keysyms. */
enum class SpecialKey(val keysym: String, val label: String) {
    ENTER("Return", "⏎"),
    BACKSPACE("BackSpace", "⌫"),
    TAB("Tab", "Tab"),
    ESCAPE("Escape", "Esc"),
    UP("Up", "↑"),
    DOWN("Down", "↓"),
    LEFT("Left", "←"),
    RIGHT("Right", "→"),
    HOME("Home", "Home"),
    END("End", "End"),
    PAGE_UP("Prior", "PgUp"),
    PAGE_DOWN("Next", "PgDn"),
    DELETE("Delete", "Del"),
}

enum class KeyModifier(val keysym: String, val label: String) {
    CTRL("ctrl", "Ctrl"),
    ALT("alt", "Alt"),
    SHIFT("shift", "Shift"),
    SUPER("super", "Super"),
}

/**
 * Keyboard → [InputCommand] mapping (pure; used by the keyboard bar and tests).
 *
 * Without modifiers, text goes out as `type` (any Unicode, xdotool maps keysyms)
 * and special keys as `key <keysym>`. With modifiers held (Ctrl/Alt from the
 * modifier row, one-shot), a character becomes a combo such as `ctrl+c` or
 * `ctrl+alt+Delete`; characters without a keysym name are typed plainly.
 */
object DesktopKeys {
    private val TOKEN = Regex("^[A-Za-z0-9_]{1,32}$")

    /** xdotool command names: a bare `key` argument equal to one would chain into it. */
    val XDOTOOL_COMMANDS: Set<String> = setOf(
        "exec", "type", "key", "keydown", "keyup", "click", "mousemove", "mousemove_relative",
        "mousedown", "mouseup", "getmouselocation", "search", "sleep", "set_window", "windowkill",
        "windowactivate", "windowfocus", "windowmap", "windowunmap", "windowclose", "windowquit",
        "windowsize", "windowmove", "windowraise", "windowminimize", "windowreparent", "windowstate",
        "getactivewindow", "getwindowfocus", "getwindowname", "getwindowpid", "getwindowgeometry",
        "getwindowclassname", "selectwindow", "behave", "behave_screen_edge", "set_desktop",
        "get_desktop", "set_num_desktops", "get_num_desktops", "set_desktop_for_window",
        "get_desktop_for_window", "get_desktop_viewport", "set_desktop_viewport", "version", "help",
    )

    /** `ctrl+shift+t`, `Return`, …: keysym-charset tokens joined by `+`, not an xdotool command. */
    fun validCombo(combo: String): Boolean {
        if (combo.isEmpty() || combo.length > 100) return false
        val parts = combo.split('+')
        if (parts.any { !TOKEN.matches(it) }) return false
        return !(parts.size == 1 && combo.lowercase() in XDOTOOL_COMMANDS)
    }

    private val CHAR_KEYSYMS: Map<Char, String> = mapOf(
        ' ' to "space", '-' to "minus", '=' to "equal", '[' to "bracketleft", ']' to "bracketright",
        ';' to "semicolon", '\'' to "apostrophe", ',' to "comma", '.' to "period", '/' to "slash",
        '\\' to "backslash", '`' to "grave", '!' to "exclam", '@' to "at", '#' to "numbersign",
        '$' to "dollar", '%' to "percent", '^' to "asciicircum", '&' to "ampersand", '*' to "asterisk",
        '(' to "parenleft", ')' to "parenright", '_' to "underscore", '+' to "plus", '{' to "braceleft",
        '}' to "braceright", ':' to "colon", '"' to "quotedbl", '<' to "less", '>' to "greater",
        '?' to "question", '|' to "bar", '~' to "asciitilde",
    )

    /** X keysym name for [c] usable in a combo, or null. Letters keep their case. */
    fun keysymFor(c: Char): String? = when {
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> c.toString()
        else -> CHAR_KEYSYMS[c]
    }

    fun combo(modifiers: Collection<KeyModifier>, keysym: String): String =
        (KeyModifier.entries.filter { it in modifiers }.map { it.keysym } + keysym).joinToString("+")

    fun special(key: SpecialKey, modifiers: Collection<KeyModifier> = emptySet()): InputCommand =
        InputCommand.Key(listOf(combo(modifiers, key.keysym)))

    /**
     * Commands for text from the soft keyboard. With [modifiers], each character
     * with a keysym becomes one combo (`ctrl+a`); otherwise the text is typed.
     * `\n` is Return and `\t` Tab in both cases.
     */
    fun text(text: String, modifiers: Collection<KeyModifier> = emptySet()): List<InputCommand> {
        if (text.isEmpty()) return emptyList()
        val clean = text.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        if (clean.isEmpty()) return emptyList()
        if (modifiers.isEmpty()) return listOf(InputCommand.Type(clean))
        val out = mutableListOf<InputCommand>()
        val plain = StringBuilder()
        fun flushPlain() { if (plain.isNotEmpty()) { out += InputCommand.Type(plain.toString()); plain.clear() } }
        for (c in clean) {
            val sym = when (c) { '\n' -> "Return"; '\t' -> "Tab"; else -> keysymFor(c) }
            if (sym == null) plain.append(c) else { flushPlain(); out += InputCommand.Key(listOf(combo(modifiers, sym))) }
        }
        flushPlain()
        return out
    }
}

/**
 * Turns soft-keyboard edits of a hidden text field into key events. The field
 * holds [SENTINEL] (so Backspace on an "empty" field is still seen) and is reset
 * after every edit. Returns the inserted text and how many characters were
 * deleted before the cursor.
 */
object ImeDiff {
    const val SENTINEL = "\u200B\u200B"

    data class Edit(val deleted: Int, val inserted: String)

    fun diff(old: String, new: String): Edit {
        var prefix = 0
        while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) suffix++
        val deleted = old.length - prefix - suffix
        val inserted = new.substring(prefix, new.length - suffix).replace("\u200B", "")
        return Edit(deleted, inserted)
    }

    /** Commands for an edit: Backspace per deleted char, then the inserted text. */
    fun commands(edit: Edit, modifiers: Collection<KeyModifier> = emptySet()): List<InputCommand> = buildList {
        repeat(edit.deleted.coerceAtMost(64)) { add(DesktopKeys.special(SpecialKey.BACKSPACE)) }
        addAll(DesktopKeys.text(edit.inserted, modifiers))
    }
}
