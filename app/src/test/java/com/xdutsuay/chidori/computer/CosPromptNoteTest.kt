package com.xdutsuay.chidori.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.xdutsuay.chidori.computer.CosPromptNote.State as S

class CosPromptNoteTest {
    @Test
    fun `disabled leaves the prompt untouched`() {
        assertEquals("", CosPromptNote.compose("", CosPromptNote.State.DISABLED))
        assertEquals("You are a pirate.", CosPromptNote.compose("You are a pirate.", CosPromptNote.State.DISABLED))
        assertNull(CosPromptNote.note(CosPromptNote.State.DISABLED))
    }

    @Test
    fun `ready note mentions alpine proot workspace cos_exec apk and limits`() {
        val p = CosPromptNote.compose("", CosPromptNote.State.READY)
        assertTrue(p.startsWith(CosPromptNote.HEADER))
        listOf("Alpine Linux 3.24", "proot", "cos_exec", "apk add", "/root", "no systemd", "no GUI")
            .forEach { assertTrue("missing '$it' in: $p", p.contains(it)) }
    }

    @Test
    fun `note is appended after the user's prompt once`() {
        val p = CosPromptNote.compose("You are terse.\n", CosPromptNote.State.READY)
        assertTrue(p.startsWith("You are terse.\n\n${CosPromptNote.HEADER}"))
        assertEquals(p, CosPromptNote.compose(p, CosPromptNote.State.READY))
        assertEquals(1, Regex(Regex.escape(CosPromptNote.HEADER)).findAll(p).count())
    }

    @Test
    fun `not set up points to the Computer screen`() {
        val p = CosPromptNote.compose("", CosPromptNote.State.NOT_SET_UP)
        assertTrue(p.contains("Computer screen"))
        assertTrue(p.contains("Install rootfs"))
        assertFalse(p.contains("You have a real Linux computer"))
    }

    @Test
    fun `state resolution`() {
        assertEquals(CosPromptNote.State.DISABLED, CosPromptNote.stateOf(false, true))
        assertEquals(CosPromptNote.State.DISABLED, CosPromptNote.stateOf(false, false))
        assertEquals(CosPromptNote.State.NOT_SET_UP, CosPromptNote.stateOf(true, false))
        assertEquals(CosPromptNote.State.READY, CosPromptNote.stateOf(true, true))
    }

    @Test
    fun `tool off downgrades an enabled computer and never offers cos_exec`() {
        assertEquals(S.TOOL_OFF, CosPromptNote.liveState(S.READY, cosExecAvailable = false))
        assertEquals(S.TOOL_OFF, CosPromptNote.liveState(S.NOT_SET_UP, cosExecAvailable = false))
        assertEquals(S.DISABLED, CosPromptNote.liveState(S.DISABLED, cosExecAvailable = false))
        assertEquals(S.READY, CosPromptNote.liveState(S.READY, cosExecAvailable = true))
        val n = CosPromptNote.note(S.TOOL_OFF)!!
        assertTrue(n.contains("Do not offer or attempt to call `cos_exec`"))
        assertFalse(n.contains("Use the `cos_exec` tool"))
    }

    @Test
    fun `turn update only when the live state changed`() {
        assertNull(CosPromptNote.turnUpdate(S.READY, S.READY))
        assertEquals("hi", CosPromptNote.withTurnUpdate("hi", null))

        val off = CosPromptNote.turnUpdate(S.READY, S.TOOL_OFF)!!
        assertTrue(off.startsWith(CosPromptNote.UPDATE_OPEN))
        assertTrue(off.endsWith(CosPromptNote.UPDATE_CLOSE))
        assertTrue(off.contains("Do not offer or attempt to call `cos_exec`"))

        val installed = CosPromptNote.turnUpdate(S.NOT_SET_UP, S.READY)!!
        assertTrue(installed.contains("Use the `cos_exec` tool"))

        val disabled = CosPromptNote.turnUpdate(S.READY, S.DISABLED)!!
        assertTrue(disabled.contains("turned off"))

        val msg = CosPromptNote.withTurnUpdate("ls please", off)
        assertTrue(msg.startsWith(CosPromptNote.UPDATE_OPEN))
        assertTrue(msg.endsWith("\n\nls please"))
    }
}
