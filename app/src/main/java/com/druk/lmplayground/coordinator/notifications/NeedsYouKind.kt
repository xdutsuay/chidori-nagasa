package com.druk.lmplayground.coordinator.notifications

/**
 * CP6 — categories surfaced in the Needs-you inbox / deep links.
 * [RUN_APPROVAL] is the existing KMA-288 desktop run gate; others are
 * first-class slots so pairing, cellular install, Stop-at-risk, and node
 * offer never hide as anonymous notifications.
 */
enum class NeedsYouKind {
    RUN_APPROVAL,
    PAIRING,
    STOP_AT_RISK,
    CELLULAR_INSTALL,
    NODE_OFFER,
    /**
     * Proactive check-in (KMA-333). Set by the check-in notifier itself.
     * [classifyNeedsYouKind] does not infer this, so a run summary cannot
     * be stolen into the check-in bucket.
     */
    CHECK_IN,
}

/**
 * Best-effort classify from a notification / log line. Unknown → [RUN_APPROVAL]
 * so existing run deeplinks keep working.
 */
fun classifyNeedsYouKind(summary: String?): NeedsYouKind {
    val s = summary?.lowercase().orEmpty()
    return when {
        s.contains("pair") -> NeedsYouKind.PAIRING
        s.contains("cellular") || s.contains("mobile data") -> NeedsYouKind.CELLULAR_INSTALL
        s.contains("node") && s.contains("offer") -> NeedsYouKind.NODE_OFFER
        s.contains("stop") && (s.contains("risk") || s.contains("destructive")) ->
            NeedsYouKind.STOP_AT_RISK
        else -> NeedsYouKind.RUN_APPROVAL
    }
}
