package com.xdutsuay.chidori.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerScreenModelTerminalTest {

    @Test
    fun `lastTerminalLine returns last non-blank line`() {
        assertEquals("b", ComputerScreenModel.lastTerminalLine("a\nb\n\n"))
    }

    @Test
    fun `lastTerminalLine returns empty for empty terminal`() {
        assertEquals("", ComputerScreenModel.lastTerminalLine(""))
    }

    @Test
    fun `lastTerminalLine trims trailing whitespace and CR`() {
        assertEquals("x", ComputerScreenModel.lastTerminalLine("  x  \r\n"))
    }

    @Test
    fun `lastTerminalLine handles crlf line endings`() {
        assertEquals("line2", ComputerScreenModel.lastTerminalLine("line1\r\nline2"))
    }

    @Test
    fun `lastTerminalLine returns empty when only blank lines`() {
        assertEquals("", ComputerScreenModel.lastTerminalLine("\r\n\r\n"))
        assertEquals("", ComputerScreenModel.lastTerminalLine("\n\n  \n"))
    }

    @Test
    fun `lastTerminalLine returns single unterminated line`() {
        assertEquals("only", ComputerScreenModel.lastTerminalLine("only"))
    }

    @Test
    fun `lastTerminalLine skips blank lines before last content`() {
        assertEquals("done", ComputerScreenModel.lastTerminalLine("step 1\nstep 2\n\n\ndone"))
    }
}
