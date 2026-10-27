package com.druk.lmplayground.coordinator.notifications

import com.druk.lmplayground.coordinator.model.CompanionNotificationEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * KMA-288 (TEST AUTHOR RED — no production code yet; this file does not
 * compile until the implementer lands the surface below).
 *
 * == IMPLEMENTER CONTRACT (make these green, do not weaken assertions) ==
 *
 * Pure needs_you dispatch hook for the companion notification consumer
 * (desktop `lclreason` main @ 79b7209 ships GET /notifications; the wire
 * event's `state` is one of "running" | "completed" | "failed" | "needs_you",
 * see internal/api/chidori_contract.go).
 *
 * Surface to implement in `com.druk.lmplayground.coordinator.notifications`:
 *
 *   fun interface NeedsYouNotifier {
 *       fun notify(runId: String, reason: String)
 *   }
 *
 *   /** Dispatches every needs_you event through [notifier]; returns the
 *    *  number of notifications dispatched. Non-needs_you events (including
 *    *  completed/failed) must NOT notify. */
 *   fun dispatchNeedsYou(
 *       events: List<CompanionNotificationEvent>,
 *       notifier: NeedsYouNotifier,
 *   ): Int
 *
 * Notes for the implementer:
 *  - `CompanionNotificationEvent` lives in `coordinator.model` (see
 *    ChidoriNotificationsApiTest's header) and carries `state` as the raw
 *    wire token — match exactly "needs_you", never a prefix/contains match.
 *  - The poll loop advances `since = cursor` after every page, so each event
 *    is delivered once per page; no dedup here (YAGNI). If redelivery ever
 *    matters (cursor loss), dedup belongs in the loop, not in this hook.
 *  - UI wiring (a real system-notification Notifier backed by
 *    NotificationManager, plus an in-app alert path) is the thin layer the
 *    ViewModel owns; this hook is the unit-testable seam.
 */
class NeedsYouDispatchTest {

    private fun event(
        id: Long,
        runId: String,
        state: String,
        reason: String = "",
    ): CompanionNotificationEvent =
        CompanionNotificationEvent(id = id, runId = runId, state = state, reason = reason, ts = 0L)

    @Test
    fun `needs_you event dispatches notify with run id and reason`() {
        val notified = mutableListOf<Pair<String, String>>()

        val dispatched = dispatchNeedsYou(
            listOf(event(1, "run-a", "needs_you", "awaiting clarification")),
            notifier = { runId, reason -> notified.add(runId to reason) },
        )

        assertEquals(listOf("run-a" to "awaiting clarification"), notified)
        assertEquals(1, dispatched)
    }

    @Test
    fun `completed and failed and running events never notify`() {
        val notified = mutableListOf<Pair<String, String>>()

        val dispatched = dispatchNeedsYou(
            listOf(
                event(1, "run-a", "completed"),
                event(2, "run-b", "failed", "exit 1"),
                event(3, "run-c", "running"),
            ),
            notifier = { runId, reason -> notified.add(runId to reason) },
        )

        assertEquals(0, dispatched)
        assertEquals(0, notified.size)
    }

    @Test
    fun `mixed page dispatches only the needs_you events in page order`() {
        val notified = mutableListOf<Pair<String, String>>()

        val dispatched = dispatchNeedsYou(
            listOf(
                event(1, "run-a", "completed"),
                event(2, "run-b", "needs_you", "needs approval"),
                event(3, "run-c", "failed", "boom"),
                event(4, "run-d", "needs_you", "needs input"),
            ),
            notifier = { runId, reason -> notified.add(runId to reason) },
        )

        assertEquals(
            listOf(
                "run-b" to "needs approval",
                "run-d" to "needs input",
            ),
            notified,
        )
        assertEquals(2, dispatched)
    }

    @Test
    fun `empty page dispatches nothing`() {
        var calls = 0

        val dispatched = dispatchNeedsYou(
            emptyList(),
            notifier = { _, _ -> calls++ },
        )

        assertEquals(0, calls)
        assertEquals(0, dispatched)
    }

    @Test
    fun `state token is matched exactly - lookalike tokens must not notify`() {
        // Guards against a contains/prefix match on "needs_you" sneaking in.
        val notified = mutableListOf<Pair<String, String>>()

        val dispatched = dispatchNeedsYou(
            listOf(
                event(1, "run-a", "not_needs_you"),
                event(2, "run-b", "needs_you_extra"),
            ),
            notifier = { runId, reason -> notified.add(runId to reason) },
        )

        assertEquals(0, dispatched)
        assertEquals(0, notified.size)
    }
}
