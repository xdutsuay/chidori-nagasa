package com.xdutsuay.chidori.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosToolDefaultsTest {
    private fun cos(computer: Boolean, override: Boolean?, global: Boolean?) =
        CosToolDefaults.resolve(CosToolDefaults.COS_EXEC, override, global, computer)

    @Test
    fun `cos_exec defaults on for new and existing chats when Computer is enabled`() {
        // Existing chat == a model/session with no stored toggle for cos_exec.
        assertTrue(cos(computer = true, override = null, global = null))
    }

    @Test
    fun `explicit off stays off - chat toggle or global switch`() {
        assertFalse(cos(computer = true, override = false, global = null))
        assertFalse(cos(computer = true, override = null, global = false))
        assertFalse(cos(computer = true, override = false, global = true))
        // chat toggle on beats a global off
        assertTrue(cos(computer = true, override = true, global = false))
    }

    @Test
    fun `matrix enabled-disabled x on-off-unset`() {
        for (override in listOf(true, false, null)) {
            for (global in listOf(true, false, null)) {
                assertFalse("computer off never offers cos_exec", cos(false, override, global))
                assertEquals(override ?: global ?: true, cos(true, override, global))
            }
        }
    }

    @Test
    fun `other tools keep the historic off-by-default rule regardless of Computer`() {
        for (computer in listOf(true, false)) {
            assertFalse(CosToolDefaults.resolve("web_search", null, null, computer))
            assertTrue(CosToolDefaults.resolve("web_search", null, true, computer))
            assertFalse(CosToolDefaults.resolve("web_search", false, true, computer))
        }
    }

    @Test
    fun `settings display shows cos_exec on until switched off`() {
        assertTrue(CosToolDefaults.globalDisplay("cos_exec", null))
        assertFalse(CosToolDefaults.globalDisplay("cos_exec", false))
        assertFalse(CosToolDefaults.globalDisplay("web_fetch", null))
    }

    // library_search: on by default, independent of the Computer toggle.

    @Test
    fun `library_search constants and default-on set`() {
        assertEquals("library_search", CosToolDefaults.LIBRARY_SEARCH)
        assertTrue(CosToolDefaults.DEFAULT_ON_TOOLS.contains(CosToolDefaults.LIBRARY_SEARCH))
    }

    @Test
    fun `library_search defaults on regardless of Computer toggle`() {
        for (override in listOf<Boolean?>(null)) {
            for (global in listOf<Boolean?>(null)) {
                assertEquals(override ?: global ?: true,
                    CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, override, global, true))
                assertEquals(override ?: global ?: true,
                    CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, override, global, false))
            }
        }
    }

    @Test
    fun `library_search respects explicit user off`() {
        // chat toggle off wins
        assertFalse(CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, false, true, true))
        assertFalse(CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, false, null, false))
        // global switch off wins when no chat toggle
        assertFalse(CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, null, false, true))
        assertFalse(CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, null, false, false))
    }

    @Test
    fun `library_search chat on beats global off and stays independent of Computer`() {
        assertTrue(CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, true, false, true))
        assertTrue(CosToolDefaults.resolve(CosToolDefaults.LIBRARY_SEARCH, true, false, false))
    }

    @Test
    fun `settings display shows library_search on until switched off`() {
        assertTrue(CosToolDefaults.globalDisplay(CosToolDefaults.LIBRARY_SEARCH, null))
        assertFalse(CosToolDefaults.globalDisplay(CosToolDefaults.LIBRARY_SEARCH, false))
    }

    @Test
    fun `other non-COS tools still default off alongside library_search`() {
        for (computer in listOf(true, false)) {
            assertFalse(CosToolDefaults.resolve("web_search", null, null, computer))
            // library_search must not flip the default for its non-COS siblings
            assertFalse(CosToolDefaults.resolve("web_fetch", null, null, computer))
        }
    }
}
