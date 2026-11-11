package com.xdutsuay.chidori.computer.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class XdotoolScriptTest {

    /** xdotool 4.x script tokenizer, reduced to what our lines can contain. */
    private fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == ' ' || c == '\t') { i++; continue }
            if (c == '"' || c == '\'') {
                val end = line.indexOf(c, i + 1)
                check(end > i) { "unterminated quote in $line" }
                out += line.substring(i + 1, end); i = end + 1
            } else {
                var j = i
                while (j < line.length && line[j] != ' ' && line[j] != '\t') j++
                check(!line.substring(i, j).contains('"') && !line.substring(i, j).contains('\'')) { "quote in bare token: $line" }
                check(line[i] != '#') { "comment: $line" }
                out += line.substring(i, j); i = j
            }
        }
        return out
    }

    /** What xdotool would type for the given lines (Return/Tab/dollar keys included). */
    private fun typed(lines: List<String>): String = buildString {
        for (l in lines) {
            assertFalse("newline inside line", l.contains('\n') || l.contains('\r'))
            assertTrue("line length", l.toByteArray().size <= XdotoolScript.MAX_LINE_BYTES)
            val t = tokenize(l)
            for (tok in t) {
                assertTrue("empty token in $l", tok.isNotEmpty())
                assertFalse("\$-expansion in $l", tok.startsWith("$"))
            }
            when (t[0]) {
                "type" -> { assertEquals(listOf("type", "--delay", "12", "--"), t.take(4)); t.drop(4).forEach { append(it) } }
                "key" -> append(mapOf("Return" to "\n", "Tab" to "\t", "dollar" to "$").getValue(t[1]))
                else -> error("unexpected command $l")
            }
        }
    }

    private fun typeLines(text: String) = XdotoolScript.lines(InputCommand.Type(text).toArgv(com.xdutsuay.chidori.computer.desktop.DesktopGeometry(10, 10)))

    @Test
    fun `plain argv stays bare`() {
        assertEquals(listOf("mousemove 10 20 click --repeat 2 1"), XdotoolScript.lines(listOf("mousemove", "10", "20", "click", "--repeat", "2", "1")))
        assertEquals(listOf("key -- ctrl+shift+t Return"), XdotoolScript.lines(listOf("key", "--", "ctrl+shift+t", "Return")))
    }

    @Test
    fun `semicolons spaces and shell syntax are quoted, never interpreted`() {
        assertEquals(listOf("type --delay 12 -- \"a; rm -rf / && echo |x\""), typeLines("a; rm -rf / && echo |x"))
        assertEquals("a; b `c` \$(d)", typed(typeLines("a; b `c` \$(d)")))
    }

    @Test
    fun `newlines and tabs become Return and Tab keys`() {
        assertEquals(
            listOf("type --delay 12 -- ls", "key Return", "type --delay 12 -- \"a b\"", "key Tab", "key Return"),
            typeLines("ls\na b\t\n"),
        )
    }

    @Test
    fun `mixed quotes are split into tokens typed back to back`() {
        val lines = typeLines("say \"hi\" it's")
        assertEquals(listOf("type --delay 12 -- 'say \"hi\" it' \"'s\""), lines)
        assertEquals("say \"hi\" it's", typed(lines))
    }

    @Test
    fun `dollar never starts a token and hash never starts a comment`() {
        assertEquals(listOf("key dollar", "type --delay 12 -- HOME"), typeLines("\$HOME"))
        assertEquals(listOf("type --delay 12 -- \"a\$b \$1\""), typeLines("a\$b \$1"))
        assertEquals(listOf("type --delay 12 -- \"#not a comment\""), typeLines("#not a comment"))
        assertEquals("\$\$x", typed(typeLines("\$\$x")))
    }

    @Test
    fun `token encoder rejects what script mode cannot carry`() {
        for (bad in listOf("", "a\nb", "a\rb", "\$HOME", "a\u0000", "both\"and'")) {
            assertThrows(XdotoolScript.UnencodableException::class.java) { XdotoolScript.token(bad) }
        }
        assertEquals("\"a b\"", XdotoolScript.token("a b"))
        assertEquals("'say \"x\"'", XdotoolScript.token("say \"x\""))
        assertEquals("\"#x\"", XdotoolScript.token("#x"))
        assertEquals("\";\"", XdotoolScript.token(";"))
    }

    @Test
    fun `long text is split under the line limit`() {
        val text = "word ".repeat(900) + "end"
        val lines = typeLines(text)
        assertTrue(lines.size > 1)
        assertEquals(text, typed(lines))
    }

    @Test
    fun `fuzz - every encoded text round-trips through the xdotool tokenizer`() {
        val alphabet = "ab Z9 \"'\$#;&|`\\\n\t-–ü€😀{}()*?~".toList()
        val rnd = Random(1234)
        repeat(2000) {
            val s = buildString { repeat(rnd.nextInt(1, 60)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            val surrogateSafe = (s.filter { !it.isSurrogate() } + if (rnd.nextBoolean()) "😀" else "").ifEmpty { "x" }
            assertEquals(surrogateSafe, typed(typeLines(surrogateSafe)))
        }
    }
}
