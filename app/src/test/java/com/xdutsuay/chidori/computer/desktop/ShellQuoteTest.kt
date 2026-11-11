package com.xdutsuay.chidori.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ShellQuoteTest {
    @Test
    fun `quotes every argument with single quotes`() {
        assertEquals("'xdotool' 'type' '--' 'hi'", ShellQuote.join(listOf("xdotool", "type", "--", "hi")))
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
        assertEquals("''", ShellQuote.quote(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NUL is rejected`() {
        ShellQuote.quote("a\u0000b")
    }

    private val hostile = listOf(
        "\$(touch /tmp/pwned)", "`id`", "a; rm -rf /", "x && y || z", "'", "''", "\"'\"",
        "a\nb", "tab\there", "*", "~", "\$HOME", "\\", "> /etc/passwd", "| sh", "-- --help",
        "ünïcödé ✓", "end'",
    )

    @Test
    fun `round-trips hostile arguments through a real sh unchanged`() {
        val sh = File("/bin/sh")
        assumeTrue("needs /bin/sh", sh.canExecute())
        val script = "for a in " + hostile.joinToString(" ") { ShellQuote.quote(it) } + "; do printf '%s\\0' \"\$a\"; done"
        val p = ProcessBuilder(sh.path, "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor())
        assertEquals(hostile, out.split('\u0000').dropLast(1))
    }
}
