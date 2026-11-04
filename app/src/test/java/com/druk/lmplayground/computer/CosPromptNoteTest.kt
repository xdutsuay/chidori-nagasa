package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
