package com.xdutsuay.chidori.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosShellLexerTest {
    @Test
    fun `tokenize simple argv`() {
        assertEquals(listOf("uname", "-a"), CosShellLexer.tokenize("uname -a"))
    }

    @Test
    fun `tokenize quoted`() {
        assertEquals(listOf("echo", "hello world"), CosShellLexer.tokenize("echo 'hello world'"))
    }

    @Test
    fun `pipes use guest sh -c`() {
        assertEquals(
            listOf("/bin/sh", "-c", "ls | wc -l"),
            CosShellLexer.toArgv("ls | wc -l"),
        )
    }

    @Test
    fun `plain line also goes through guest sh -c`() {
        assertEquals(listOf("/bin/sh", "-c", "uname -a"), CosShellLexer.toArgv("uname -a"))
    }

    @Test
    fun `tokenize empty returns empty`() {
        assertEquals(emptyList<String>(), CosShellLexer.tokenize(""))
    }

    @Test
    fun `tokenize only spaces returns empty`() {
        assertEquals(emptyList<String>(), CosShellLexer.tokenize("   \t  "))
    }

    @Test
    fun `toArgv empty and spaces return empty`() {
        assertEquals(emptyList<String>(), CosShellLexer.toArgv(""))
        assertEquals(emptyList<String>(), CosShellLexer.toArgv("  \n\t "))
    }

    @Test
    fun `tokenize double quotes`() {
        assertEquals(listOf("echo", "hello world"), CosShellLexer.tokenize("echo \"hello world\""))
    }

    @Test
    fun `tokenize mixed quotes keeps inner literal`() {
        // Not nested same-delimiter quotes; inner opposite quote is literal.
        assertEquals(
            listOf("echo", "say \"hi\""),
            CosShellLexer.tokenize("echo 'say \"hi\"'"),
        )
        assertEquals(
            listOf("echo", "say 'hi'"),
            CosShellLexer.tokenize("echo \"say 'hi'\""),
        )
    }

    @Test
    fun `tokenize unclosed quote keeps remainder in token`() {
        assertEquals(listOf("echo", "hello"), CosShellLexer.tokenize("echo 'hello"))
    }

    @Test
    fun `toArgv uses sh -c for and-or metacharacters`() {
        assertEquals(
            listOf("/bin/sh", "-c", "true && echo ok"),
            CosShellLexer.toArgv("true && echo ok"),
        )
        assertEquals(
            listOf("/bin/sh", "-c", "false || echo fail"),
            CosShellLexer.toArgv("false || echo fail"),
        )
    }

    @Test
    fun `toArgv keeps quotes for the guest shell and never uses Android sh`() {
        val argv = CosShellLexer.toArgv("  echo 'a b'  ")
        assertEquals(listOf("/bin/sh", "-c", "echo 'a b'"), argv)
        assertTrue(argv.none { it == "/system/bin/sh" })
    }
}
