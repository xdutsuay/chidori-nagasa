package com.druk.lmplayground.computer

/**
 * Minimal shell-ish argv splitter for the COS terminal input box.
 * Supports simple quotes; not a full POSIX shell.
 */
object CosShellLexer {
    fun tokenize(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < trimmed.length) {
            val c = trimmed[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        quote = null
                    } else {
                        buf.append(c)
                    }
                }
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (buf.isNotEmpty()) {
                        out += buf.toString()
                        buf.clear()
                    }
                }
                else -> buf.append(c)
            }
            i++
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /**
     * Guest argv for a COS command line: always Alpine's `/bin/sh -c <line>` with the
     * whole trimmed line as ONE argument (pipes, `&&`, quotes, globs and `;` are
     * interpreted by the guest shell inside proot, never by Android's /system/bin/sh).
     * [LocalComputerRuntime.execStreaming] wraps this with [ProotCommand.buildArgv].
     * Returns empty for a blank line.
     */
    fun toArgv(line: String): List<String> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        return ProotCommand.guestShellArgv(t)
    }
}
