package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.druk.lmplayground.computer.CosPromptNote.State as S

class CosPromptNoteDesktopTest {
    @Test
    fun `desktop tools upgrade the live state only when offered`() {
        assertEquals(S.READY_DESKTOP, CosPromptNote.liveState(S.READY, cosExecAvailable = true, desktopToolsAvailable = true))
        assertEquals(S.DESKTOP_ONLY, CosPromptNote.liveState(S.READY, cosExecAvailable = false, desktopToolsAvailable = true))
        assertEquals(S.READY, CosPromptNote.liveState(S.READY, cosExecAvailable = true, desktopToolsAvailable = false))
        assertEquals(S.TOOL_OFF, CosPromptNote.liveState(S.READY, cosExecAvailable = false, desktopToolsAvailable = false))
        assertEquals(S.DISABLED, CosPromptNote.liveState(S.DISABLED, cosExecAvailable = true, desktopToolsAvailable = true))
        assertEquals(S.NOT_SET_UP, CosPromptNote.liveState(S.NOT_SET_UP, cosExecAvailable = true, desktopToolsAvailable = true))
    }

    @Test
    fun `desktop note mentions both tools and drops the no-GUI limit`() {
        val n = CosPromptNote.note(S.READY_DESKTOP)!!
        assertTrue(n.contains("cos_exec"))
        assertTrue(n.contains("cos_screenshot"))
        assertTrue(n.contains("cos_gui"))
        assertTrue(n.contains("scale"))
        assertFalse(n.contains("no GUI"))
        val only = CosPromptNote.note(S.DESKTOP_ONLY)!!
        assertTrue(only.contains("cos_gui"))
        assertTrue(only.contains("do not call it"))
        // Non-desktop notes never advertise the desktop tools.
        for (s in listOf(S.READY, S.NOT_SET_UP, S.TOOL_OFF)) {
            val t = CosPromptNote.note(s)!!
            assertFalse(s.name, t.contains("cos_screenshot"))
            assertFalse(s.name, t.contains("cos_gui"))
        }
    }

    @Test
    fun `desktop start and stop produce per-turn updates`() {
        val up = CosPromptNote.turnUpdate(S.READY, S.READY_DESKTOP)
        assertNotNull(up)
        assertTrue(up!!.startsWith(CosPromptNote.UPDATE_OPEN))
        assertTrue(up.contains("cos_screenshot"))
        val down = CosPromptNote.turnUpdate(S.READY_DESKTOP, S.READY)!!
        assertTrue(down.contains("no GUI"))
        assertEquals(null, CosPromptNote.turnUpdate(S.READY_DESKTOP, S.READY_DESKTOP))
    }
}
