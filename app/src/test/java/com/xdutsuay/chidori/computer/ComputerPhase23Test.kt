package com.xdutsuay.chidori.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerPhase23Test {

    @Test
    fun `resource gates block low battery`() {
        assertFalse(ComputerResourceGates.allowHeavyWork(batteryPercent = 10, thermalStatus = 0))
        assertTrue(ComputerResourceGates.allowHeavyWork(batteryPercent = 50, thermalStatus = 0))
    }

    @Test
    fun `resource gates block severe thermal`() {
        assertFalse(ComputerResourceGates.allowHeavyWork(batteryPercent = 80, thermalStatus = 3))
        assertTrue(ComputerResourceGates.allowHeavyWork(batteryPercent = 80, thermalStatus = 2))
    }

    @Test
    fun `resource gates battery boundary at MIN_BATTERY_PERCENT`() {
        val min = ComputerResourceGates.MIN_BATTERY_PERCENT
        assertFalse(ComputerResourceGates.allowHeavyWork(batteryPercent = min - 1, thermalStatus = 0))
        assertTrue(ComputerResourceGates.allowHeavyWork(batteryPercent = min, thermalStatus = 0))
        assertFalse(ComputerResourceGates.allowHeavyWork(batteryPercent = 0, thermalStatus = 0))
    }

    @Test
    fun `resource gates thermal boundary at MAX_THERMAL_STATUS`() {
        val max = ComputerResourceGates.MAX_THERMAL_STATUS
        assertTrue(ComputerResourceGates.allowHeavyWork(batteryPercent = 80, thermalStatus = max))
        assertFalse(ComputerResourceGates.allowHeavyWork(batteryPercent = 80, thermalStatus = max + 1))
        assertFalse(ComputerResourceGates.allowHeavyWork(batteryPercent = 80, thermalStatus = 6))
    }

    @Test
    fun `blockReason prefers battery message over thermal`() {
        val reason = ComputerResourceGates.blockReason(batteryPercent = 5, thermalStatus = 4)
        assertEquals("Battery too low for COS work (5%)", reason)
    }

    @Test
    fun `blockReason reports thermal when battery ok`() {
        val reason = ComputerResourceGates.blockReason(batteryPercent = 50, thermalStatus = 3)
        assertEquals("Device too warm for COS work (thermal=3)", reason)
    }

    @Test
    fun `blockReason null when gates allow`() {
        assertEquals(
            null,
            ComputerResourceGates.blockReason(
                batteryPercent = ComputerResourceGates.MIN_BATTERY_PERCENT,
                thermalStatus = ComputerResourceGates.MAX_THERMAL_STATUS,
            ),
        )
    }

    @Test
    fun `pruner deletes oldest scratch before protected rootfs`() {
        val plan = ComputerPruner.planPrune(
            entries = listOf(
                ComputerPruner.Entry("scratch/old.bin", 50, 1L),
                ComputerPruner.Entry("scratch/new.bin", 50, 9L),
                ComputerPruner.Entry("rootfs/.cos_ready", 10, 1L),
            ),
            usedBytes = 150,
            softCapBytes = 100,
        )
        // Deleting oldest 50 bytes brings 150 → 100, which meets the soft cap.
        assertEquals(listOf("scratch/old.bin"), plan)
        assertFalse(plan.any { it.startsWith("rootfs") })
    }

    @Test
    fun `session store stable until rotated`() {
        val store = CosSessionStore(InMemorySessionBackend())
        val a = store.currentSessionId()
        val b = store.currentSessionId()
        assertEquals(a, b)
        val c = store.rotateSessionId()
        assertNotEquals(a, c)
        assertEquals(c, store.currentSessionId())
    }

    @Test
    fun `recipe catalog install unknown fails`() {
        val store = CosRecipeStore()
        assertFalse(store.install("nope"))
        assertTrue(store.install("smoke-shell"))
        assertTrue(store.isInstalled("smoke-shell"))
    }
}
