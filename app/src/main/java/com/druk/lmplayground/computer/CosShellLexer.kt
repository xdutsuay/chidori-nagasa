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

    /** Prefer `sh -c` for lines with shell metacharacters. */
    fun toArgv(line: String): List<String> {
        val t = line.trim()
        if (t.isEmpty()) return emptyList()
        val needsShell = t.any { it in "|&;<>*(){}[]$?`\n" } || t.contains("&&") || t.contains("||")
        return if (needsShell) {
            listOf("/system/bin/sh", "-c", t)
        } else {
            val tokens = tokenize(t)
            if (tokens.isEmpty()) emptyList() else tokens
        }
    }
}
