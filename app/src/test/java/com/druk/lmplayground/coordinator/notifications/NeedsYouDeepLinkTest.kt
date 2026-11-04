package com.druk.lmplayground.coordinator.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * KMA-288 follow-up (TEST AUTHOR RED — no production code yet; this file does
 * not compile until the implementer lands the surface below).
 *
 * == IMPLEMENTER CONTRACT (make these green, do not weaken assertions) ==
 *
 * Deep link for tapping a needs_you system notification: the notification
 * currently posted by `NeedsYouNotification.post` launches MainActivity with
 * no extras, so the user lands in Settings and has to hunt for the run. The
 * tap must instead deep-link straight into the paired desktop monitor for
 * that run (instance_id + run_id).
 *
 * Surface to implement in `com.druk.lmplayground.coordinator.notifications`:
 *
 *   data class NeedsYouDeepLink(val instanceId: String, val runId: String)
 *
 *   /** Intent action for opening a needs_you run from a notification tap. */
 *   const val ACTION_OPEN_NEEDS_YOU = "com.xdutsuay.chidori.nagasa.action.OPEN_NEEDS_YOU"
 *
 *   const val EXTRA_NEEDS_YOU_INSTANCE_ID = "com.xdutsuay.chidori.nagasa.extra.NEEDS_YOU_INSTANCE_ID"
 *   const val EXTRA_NEEDS_YOU_RUN_ID = "com.xdutsuay.chidori.nagasa.extra.NEEDS_YOU_RUN_ID"
 *
 *   /** Parse a deep link from an Intent. Requires ACTION_OPEN_NEEDS_YOU (or
 *    *  matching action constant) AND non-blank instanceId + runId extras.
 *    *  Returns null for MAIN/LAUNCHER, missing extras, or blank values. */
 *   fun parseNeedsYouDeepLink(action: String?, instanceId: String?, runId: String?): NeedsYouDeepLink?
 *
 *   /**
 *    * When a deep link arrives before paired instances are loaded, hold it.
 *    * Returns the link once [pairedInstanceIds] contains instanceId; otherwise
 *    * null (still pending — caller must keep the pending value).
 *    * Blank instanceId/runId must never resolve.
 *    */
 *   fun resolveNeedsYouDeepLink(
 *       pending: NeedsYouDeepLink?,
 *       pairedInstanceIds: Set<String>,
 *   ): NeedsYouDeepLink?
 *
 * Notes for the implementer:
 *  - These are pure String-in/String-out JVM functions; keep Intent parsing
 *    (reading the action + the two extras) in the thin Activity/ViewModel
 *    wiring layer that calls parseNeedsYouDeepLink — no Robolectric needed.
 *  - "Blank" means empty or whitespace-only (Kotlin `isBlank`); a missing
 *    extra (null) is also treated as blank.
 *  - Wire-up contract for the notification side: NeedsYouNotification.post
 *    must receive the instanceId alongside runId, set the Intent action to
 *    ACTION_OPEN_NEEDS_YOU, and put both ids under the EXTRA_* constants
 *    (with FLAG_ACTIVITY_SINGLE_TOP preserved). The receiver (MainActivity /
 *    monitor ViewModel) parses via parseNeedsYouDeepLink and holds the
 *    pending link via resolveNeedsYouDeepLink until paired instances load —
 *    a tap arriving before the pairing list loads must still land on the
 *    right monitor, not silently drop.
 */
class NeedsYouDeepLinkTest {

    // ---- parseNeedsYouDeepLink -------------------------------------------------

    @Test
    fun `parse with correct action and both extras returns deep link`() {
        val link = parseNeedsYouDeepLink(
            action = ACTION_OPEN_NEEDS_YOU,
            instanceId = "inst-1",
            runId = "run-a",
        )

        assertEquals(NeedsYouDeepLink(instanceId = "inst-1", runId = "run-a"), link)
    }

    @Test
    fun `parse with MAIN launch action returns null`() {
        // A plain launcher tap (MainActivity's MAIN/LAUNCHER intent) must not
        // be mistaken for a deep link even if stale extras linger on the
        // launch intent.
        val link = parseNeedsYouDeepLink(
            action = "android.intent.action.MAIN",
            instanceId = "inst-1",
            runId = "run-a",
        )

        assertNull(link)
    }

    @Test
    fun `parse with correct action but blank or missing run id returns null`() {
        assertNull(parseNeedsYouDeepLink(ACTION_OPEN_NEEDS_YOU, "inst-1", null))
        assertNull(parseNeedsYouDeepLink(ACTION_OPEN_NEEDS_YOU, "inst-1", ""))
        assertNull(parseNeedsYouDeepLink(ACTION_OPEN_NEEDS_YOU, "inst-1", "   "))
    }

    @Test
    fun `parse with correct action but blank or missing instance id returns null`() {
        assertNull(parseNeedsYouDeepLink(ACTION_OPEN_NEEDS_YOU, null, "run-a"))
        assertNull(parseNeedsYouDeepLink(ACTION_OPEN_NEEDS_YOU, "", "run-a"))
        assertNull(parseNeedsYouDeepLink(ACTION_OPEN_NEEDS_YOU, "  ", "run-a"))
    }

    @Test
    fun `parse with null action returns null`() {
        assertNull(parseNeedsYouDeepLink(action = null, instanceId = "inst-1", runId = "run-a"))
    }

    // ---- resolveNeedsYouDeepLink ------------------------------------------------

    @Test
    fun `resolve with no pending link returns null`() {
        assertNull(resolveNeedsYouDeepLink(pending = null, pairedInstanceIds = setOf("inst-1")))
    }

    @Test
    fun `resolve when instance not yet in paired set returns null - still pending`() {
        // The caller must treat null as "keep holding the pending value",
        // because pairing discovery often lands after the notification tap.
        val pending = NeedsYouDeepLink(instanceId = "inst-1", runId = "run-a")

        assertNull(resolveNeedsYouDeepLink(pending, pairedInstanceIds = setOf("inst-2", "inst-3")))
    }

    @Test
    fun `resolve when instance joins paired set returns the same link`() {
        val pending = NeedsYouDeepLink(instanceId = "inst-1", runId = "run-a")

        val resolved = resolveNeedsYouDeepLink(pending, pairedInstanceIds = setOf("inst-1", "inst-2"))

        assertEquals(pending, resolved)
    }

    @Test
    fun `resolve with blank fields in pending never resolves even if id is in paired set`() {
        // Defense in depth: a blank instanceId should never have been parsed,
        // but if one ever reaches the pending slot it must not resolve just
        // because the paired set happens to contain the empty string.
        assertNull(resolveNeedsYouDeepLink(pending = NeedsYouDeepLink("", "run-a"), pairedInstanceIds = setOf("")))
        assertNull(resolveNeedsYouDeepLink(pending = NeedsYouDeepLink("inst-1", ""), pairedInstanceIds = setOf("inst-1")))
        assertNull(resolveNeedsYouDeepLink(pending = NeedsYouDeepLink("  ", "run-a"), pairedInstanceIds = setOf("  ")))
    }
}
