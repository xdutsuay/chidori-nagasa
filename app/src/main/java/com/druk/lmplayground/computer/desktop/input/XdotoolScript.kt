package com.druk.lmplayground.computer.desktop.input

/**
 * Encodes xdotool argv into lines for `xdotool -` (script mode on stdin).
 *
 * Script-mode tokenizer facts (xdotool 3.20160805 .. 4.20260303, `script_main`):
 * - one command line per `\n`, executed as soon as it is read (fgets loop);
 * - tokens split on space/tab; a token starting with `"` or `'` runs to the next
 *   same quote character, with NO escapes (so a token cannot contain its own quote);
 * - after quote removal, a token whose first character is `$` is replaced by an
 *   environment variable / positional argument, and an unknown `$N` or unset
 *   variable makes xdotool EXIT (the whole session dies);
 * - an empty quoted token (`""`) ends parsing of the rest of the line (4.x);
 * - `#` at a token start (4.x) or line start (3.x) is a comment;
 * - lines are read with a 4096-byte buffer.
 *
 * Therefore every token is: non-empty, without `\n`/`\r`/NUL, never starting with
 * `$`, quoted unless it is a plain word, and every line stays under [MAX_LINE_BYTES].
 * Text for `type` is split into several tokens on the same `type` line (xdotool
 * types all its arguments back to back): a `$` that would start a token is attached
 * to the previous token, or sent as `key dollar` when there is none; `\n` and `\t`
 * become `key Return` / `key Tab`.
 */
object XdotoolScript {
    const val MAX_LINE_BYTES = 2000
    private const val MAX_TOKEN_CHARS = 400
    private val PLAIN = Regex("^[A-Za-z0-9_+=:,./@%-]+$")

    class UnencodableException(message: String) : IllegalArgumentException(message)

    /** Lines (without `\n`) for one argv. `type ... -- TEXT` may expand to several lines. */
    fun lines(argv: List<String>): List<String> {
        require(argv.isNotEmpty()) { "empty argv" }
        if (argv[0] == "type") {
            val sep = argv.indexOf("--")
            if (sep >= 0 && sep == argv.lastIndex - 1) return typeLines(argv.subList(0, sep + 1), argv.last())
        }
        return listOf(line(argv))
    }

    /** One line from tokens that need no splitting; throws [UnencodableException] otherwise. */
    fun line(tokens: List<String>): String {
        val l = tokens.joinToString(" ") { token(it) }
        if (l.toByteArray().size > MAX_LINE_BYTES) throw UnencodableException("line too long")
        return l
    }

    fun token(t: String): String {
        if (t.isEmpty()) throw UnencodableException("empty token")
        if (t.any { it == '\n' || it == '\r' || it == '\u0000' }) throw UnencodableException("newline/NUL in token")
        if (t[0] == '$') throw UnencodableException("token starts with \$")
        if (PLAIN.matches(t) && t[0] != '#') return t
        return when {
            '"' !in t -> "\"$t\""
            '\'' !in t -> "'$t'"
            else -> throw UnencodableException("token contains both quote characters")
        }
    }

    /** `type` text → script lines; [prefix] is the argv up to and including `--`. */
    fun typeLines(prefix: List<String>, text: String): List<String> {
        val out = mutableListOf<String>()
        val tokens = mutableListOf<StringBuilder>()
        fun flush() {
            if (tokens.isEmpty()) return
            // Pack tokens into lines under the byte budget.
            var current = mutableListOf<String>()
            fun emit() { if (current.isNotEmpty()) out += line(prefix + current); current = mutableListOf() }
            for (t in tokens) {
                current += t.toString()
                if (line(prefix + current).toByteArray().size > MAX_LINE_BYTES / 2) emit()
            }
            emit()
            tokens.clear()
        }
        for (c in text) {
            when (c) {
                '\n' -> { flush(); out += line(listOf("key", "Return")) }
                '\t' -> { flush(); out += line(listOf("key", "Tab")) }
                else -> {
                    val cur = tokens.lastOrNull()
                    val needNew = cur == null ||
                        cur.length >= MAX_TOKEN_CHARS && c != '$' && !c.isLowSurrogate() ||
                        (c == '"' && '\'' in cur) || (c == '\'' && '"' in cur)
                    if (!needNew) {
                        cur!!.append(c)
                    } else if (c == '$') {
                        // Only reached when there is no current token: a token must not start with '$'.
                        flush(); out += line(listOf("key", "dollar"))
                    } else {
                        tokens += StringBuilder().append(c)
                    }
                }
            }
        }
        flush()
        return out
    }
}
