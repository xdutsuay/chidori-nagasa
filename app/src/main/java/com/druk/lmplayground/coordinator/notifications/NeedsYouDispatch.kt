package com.druk.lmplayground.coordinator.notifications

import com.druk.lmplayground.coordinator.model.CompanionNotificationEvent

/**
 * Pure needs_you dispatch hook for the companion notification consumer
 * (KMA-288; desktop ships GET /notifications — WIRE_CONTRACT.md). The wire
 * event's `state` is one of "running" | "completed" | "failed" |
 * "needs_you" (internal/api/chidori_contract.go); only the exact
 * "needs_you" token may notify.
 */
fun interface NeedsYouNotifier {
    fun notify(runId: String, reason: String)
}

/** The one wire token that means "desktop is blocked on the user". Matched
 * exactly — no prefix/contains — so lookalike tokens never notify. */
internal const val NEEDS_YOU_WIRE_TOKEN = "needs_you"

/** Dispatches every needs_you event through [notifier] in page order;
 * returns the number of notifications dispatched. Non-needs_you events
 * (including completed/failed) must NOT notify.
 *
 * The poll loop advances `since = cursor` after every page, so each event
 * is delivered once per page; no dedup here (YAGNI). If redelivery ever
 * matters (cursor loss), dedup belongs in the loop, not in this hook. */
fun dispatchNeedsYou(
    events: List<CompanionNotificationEvent>,
    notifier: NeedsYouNotifier,
): Int {
    var dispatched = 0
    for (event in events) {
        if (event.state == NEEDS_YOU_WIRE_TOKEN) {
            notifier.notify(event.runId, event.reason)
            dispatched++
        }
    }
    return dispatched
}
