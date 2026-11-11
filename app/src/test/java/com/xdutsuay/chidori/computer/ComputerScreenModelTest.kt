package com.xdutsuay.chidori.computer

import com.xdutsuay.chidori.computer.ComputerScreenModel.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerScreenModelTest {

    private val ready = ComputerUiState(
        enabled = true,
        status = ComputerStatus.Ready,
        sessionId = "abcdef0123456789",
        cwd = "root",
        rootfsMode = "alpine-tar",
        rootfsUsedBytes = 3L * 1024 * 1024,
    )

    @Test
    fun `every debug action lives in the overflow menu`() {
        val actions = ComputerScreenModel.menuItems(ready).map { it.action }
        assertEquals(Action.entries.toList(), actions)
    }

    @Test
    fun `install desktop needs an idle rootfs and an idle desktop`() {
        fun on(s: ComputerUiState, desktopIdle: Boolean = true) =
            ComputerScreenModel.menuItems(s, desktopIdle).single { it.action == Action.INSTALL_DESKTOP }.enabled
        assertTrue(on(ready))
        assertTrue(on(ready.copy(status = ComputerStatus.Running)))
        assertFalse(on(ready.copy(status = ComputerStatus.MissingRootfs)))
        assertFalse(on(ready.copy(busy = true)))
        assertFalse(on(ready.copy(enabled = false)))
        assertFalse(on(ready, desktopIdle = false))
    }

    @Test
    fun `menu enablement follows enabled and busy`() {
        val idle = ComputerScreenModel.menuItems(ready).associate { it.action to it.enabled }
        assertTrue(idle.values.all { it })

        val busy = ComputerScreenModel.menuItems(ready.copy(busy = true)).associate { it.action to it.enabled }
        assertFalse(busy.getValue(Action.INSTALL_ROOTFS))
        assertFalse(busy.getValue(Action.REINSTALL_ROOTFS))
        assertFalse(busy.getValue(Action.SMOKE))
        assertTrue(busy.getValue(Action.PRUNE))
        assertTrue(busy.getValue(Action.CLEAR_TERMINAL))

        val off = ComputerScreenModel.menuItems(ready.copy(enabled = false)).associate { it.action to it.enabled }
        assertFalse(off.getValue(Action.SMOKE))
        assertFalse(off.getValue(Action.PRUNE))
        assertTrue(off.getValue(Action.NEW_SESSION))
        assertTrue(off.getValue(Action.CLEAR_TERMINAL))
    }

    @Test
    fun `status line is one compact line with state and cwd`() {
        val line = ComputerScreenModel.statusLine(ready)
        assertEquals("Ready · alpine · COS storage 3.0 MB · /root", line)
        assertFalse(line.contains("Rootfs"))
        assertFalse(line.contains("proot"))
        assertFalse(line.contains("#"))
    }

    @Test
    fun `status line flattens multi-line errors and hides runtime details`() {
        val line = ComputerScreenModel.statusLine(
            ready.copy(status = ComputerStatus.Error("boom\nsecond line\r\nthird"), prootNoSeccomp = true),
        )
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertTrue(line.startsWith("Error: boom second line third · "))
        assertFalse(line.contains("proot"))
        assertFalse(line.contains('#'))
    }

    @Test
    fun `status line when disabled and not set up`() {
        assertTrue(
            ComputerScreenModel.statusLine(ComputerUiState()).startsWith("Off · none · COS storage "),
        )
        assertEquals("—", ComputerScreenModel.runtimeLabel(ComputerUiState()))
        assertEquals(
            "Not set up",
            ComputerScreenModel.statusLabel(ComputerStatus.MissingRootfs),
        )
    }

    @Test
    fun `enter or send only runs with non-blank input while idle`() {
        assertFalse(ComputerScreenModel.canRun(ready))
        assertTrue(ComputerScreenModel.canRun(ready.copy(input = "uname -a")))
        assertFalse(ComputerScreenModel.canRun(ready.copy(input = "  ")))
        assertFalse(ComputerScreenModel.canRun(ready.copy(input = "ls", busy = true)))
        assertFalse(ComputerScreenModel.canRun(ready.copy(input = "ls", enabled = false)))
    }

    @Test
    fun `recipes become menu items and installed ones are disabled`() {
        val first = ready.recipes.first()
        val items = ComputerScreenModel.recipeItems(ready.copy(installedRecipes = setOf(first.id)))
        assertEquals(ready.recipes.size, items.size)
        val item = items.first { it.id == first.id }
        assertTrue(item.installed)
        assertFalse(item.enabled)
        assertTrue(items.filter { it.id != first.id }.all { it.enabled && !it.installed })
    }
}
