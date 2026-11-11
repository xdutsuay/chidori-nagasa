package com.xdutsuay.chidori.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP10 tests for one routed user turn (target route + mention detection).
 *
 * Production symbols exercised (package com.xdutsuay.chidori.hybrid):
 *  - data class HybridTurnPlan(
 *        selectedRoute: TurnRoute,   // what the composer chip had
 *        effectiveRoute: TurnRoute,  // target route after fallback + mention override
 *        desktopMentioned: Boolean,  // whether the text contained @Desktop
 *    )
 *  - HybridTurnPlan.plan(
 *        userText: String, selected: TurnRoute,
 *        desktopPaired: Boolean, apiConfigured: Boolean
 *    ): HybridTurnPlan
 *
 * Contract:
 *  - @Desktop mention on a PAIRED desktop overrides the chip to DESKTOP.
 *  - An unpaired desktop never wins: DESKTOP selection (chip or mention) falls
 *    back to LOCAL (or to the selected route if that resolves elsewhere).
 *  - API selection only survives when apiConfigured; otherwise LOCAL.
 */
class HybridTurnPlanTest {

    // ---- LOCAL chip: plain, mention-free turns ----

    @Test
    fun `plan local chip with nothing available stays local`() {
        val plan = HybridTurnPlan.plan(
            userText = "do the thing locally",
            selected = TurnRoute.LOCAL,
            desktopPaired = false,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.LOCAL, plan.selectedRoute)
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    @Test
    fun `plan local chip stays local even when everything is available`() {
        val plan = HybridTurnPlan.plan(
            userText = "do the thing locally",
            selected = TurnRoute.LOCAL,
            desktopPaired = true,
            apiConfigured = true,
        )
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    // ---- DESKTOP chip ----

    @Test
    fun `plan desktop chip paired goes to desktop`() {
        val plan = HybridTurnPlan.plan(
            userText = "build this on the big machine",
            selected = TurnRoute.DESKTOP,
            desktopPaired = true,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.DESKTOP, plan.selectedRoute)
        assertEquals(TurnRoute.DESKTOP, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    @Test
    fun `plan desktop chip unpaired falls back to local`() {
        val plan = HybridTurnPlan.plan(
            userText = "build this on the big machine",
            selected = TurnRoute.DESKTOP,
            desktopPaired = false,
            apiConfigured = true,
        )
        assertEquals(TurnRoute.DESKTOP, plan.selectedRoute)
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    // ---- API chip ----

    @Test
    fun `plan api chip configured goes to api`() {
        val plan = HybridTurnPlan.plan(
            userText = "answer with the cloud model",
            selected = TurnRoute.API,
            desktopPaired = false,
            apiConfigured = true,
        )
        assertEquals(TurnRoute.API, plan.selectedRoute)
        assertEquals(TurnRoute.API, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    @Test
    fun `plan api chip not configured falls back to local`() {
        val plan = HybridTurnPlan.plan(
            userText = "answer with the cloud model",
            selected = TurnRoute.API,
            desktopPaired = true,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.API, plan.selectedRoute)
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    // ---- @Desktop mention overrides ----

    @Test
    fun `plan mention with paired desktop overrides local chip to desktop`() {
        val plan = HybridTurnPlan.plan(
            userText = "@Desktop take this turn",
            selected = TurnRoute.LOCAL,
            desktopPaired = true,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.LOCAL, plan.selectedRoute)
        assertEquals(TurnRoute.DESKTOP, plan.effectiveRoute)
        assertTrue(plan.desktopMentioned)
    }

    @Test
    fun `plan mention with paired desktop overrides api chip to desktop`() {
        val plan = HybridTurnPlan.plan(
            userText = "hey @Desktop, you handle this one",
            selected = TurnRoute.API,
            desktopPaired = true,
            apiConfigured = true,
        )
        assertEquals(TurnRoute.API, plan.selectedRoute)
        assertEquals(TurnRoute.DESKTOP, plan.effectiveRoute)
        assertTrue(plan.desktopMentioned)
    }

    @Test
    fun `plan mention keeps desktop chip when already selected and paired`() {
        val plan = HybridTurnPlan.plan(
            userText = "@Desktop please compile",
            selected = TurnRoute.DESKTOP,
            desktopPaired = true,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.DESKTOP, plan.selectedRoute)
        assertEquals(TurnRoute.DESKTOP, plan.effectiveRoute)
        assertTrue(plan.desktopMentioned)
    }

    @Test
    fun `plan mention is case insensitive`() {
        val plan = HybridTurnPlan.plan(
            userText = "cc @desktop run the tests",
            selected = TurnRoute.LOCAL,
            desktopPaired = true,
            apiConfigured = false,
        )
        assertTrue(plan.desktopMentioned)
        assertEquals(TurnRoute.DESKTOP, plan.effectiveRoute)
    }

    // ---- mention but desktop unpaired: no forced override, normal fallback applies ----

    @Test
    fun `plan mention with unpaired desktop keeps local chip on local`() {
        val plan = HybridTurnPlan.plan(
            userText = "@Desktop are you there? guess not",
            selected = TurnRoute.LOCAL,
            desktopPaired = false,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.LOCAL, plan.selectedRoute)
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
        assertTrue(plan.desktopMentioned)
    }

    @Test
    fun `plan mention with unpaired desktop falls back from desktop chip to local`() {
        val plan = HybridTurnPlan.plan(
            userText = "@Desktop are you there? guess not",
            selected = TurnRoute.DESKTOP,
            desktopPaired = false,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.DESKTOP, plan.selectedRoute)
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
        assertTrue(plan.desktopMentioned)
    }

    @Test
    fun `plan mention does not block api chip when desktop unpaired and api configured`() {
        val plan = HybridTurnPlan.plan(
            userText = "@Desktop unreachable, so @Desktop-less turn goes to cloud",
            selected = TurnRoute.API,
            desktopPaired = false,
            apiConfigured = true,
        )
        assertTrue(plan.desktopMentioned)
        assertEquals(TurnRoute.API, plan.effectiveRoute)
    }

    // ---- text is otherwise opaque to the plan ----

    @Test
    fun `plan empty text with api chip configured goes to api`() {
        val plan = HybridTurnPlan.plan(
            userText = "",
            selected = TurnRoute.API,
            desktopPaired = false,
            apiConfigured = true,
        )
        assertEquals(TurnRoute.API, plan.effectiveRoute)
        assertFalse(plan.desktopMentioned)
    }

    @Test
    fun `plan records selected route unchanged even when fallback fires`() {
        val plan = HybridTurnPlan.plan(
            userText = "whatever",
            selected = TurnRoute.API,
            desktopPaired = false,
            apiConfigured = false,
        )
        assertEquals(TurnRoute.API, plan.selectedRoute)
        assertEquals(TurnRoute.LOCAL, plan.effectiveRoute)
    }
}
