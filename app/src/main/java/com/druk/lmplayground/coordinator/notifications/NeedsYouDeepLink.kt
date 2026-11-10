package com.druk.lmplayground.coordinator.notifications

/**
 * Deep link for a needs_you system-notification tap (KMA-288 daily-use
 * follow-on). Pure String APIs so JVM unit tests stay Robolectric-free;
 * Activity/ViewModel wiring reads Intent extras then calls these.
 */
data class NeedsYouDeepLink(val instanceId: String, val runId: String)

/** Intent action for opening a needs_you run from a notification tap. */
const val ACTION_OPEN_NEEDS_YOU = "com.xdutsuay.chidori.nagasa.action.OPEN_NEEDS_YOU"

const val EXTRA_NEEDS_YOU_INSTANCE_ID =
    "com.xdutsuay.chidori.nagasa.extra.NEEDS_YOU_INSTANCE_ID"

const val EXTRA_NEEDS_YOU_RUN_ID =
    "com.xdutsuay.chidori.nagasa.extra.NEEDS_YOU_RUN_ID"

/**
 * Parse a deep link from Intent fields. Requires [ACTION_OPEN_NEEDS_YOU] and
 * non-blank instanceId + runId. Returns null for MAIN/LAUNCHER, missing
 * extras, or blank values.
 */
fun parseNeedsYouDeepLink(
    action: String?,
    instanceId: String?,
    runId: String?,
): NeedsYouDeepLink? {
    if (action != ACTION_OPEN_NEEDS_YOU) return null
    val instance = instanceId?.takeUnless { it.isBlank() } ?: return null
    val run = runId?.takeUnless { it.isBlank() } ?: return null
    return NeedsYouDeepLink(instanceId = instance, runId = run)
}

/**
 * When a deep link arrives before paired instances are loaded, hold it.
 * Returns the link once [pairedInstanceIds] contains instanceId; otherwise
 * null (still pending — caller must keep the pending value).
 * Blank instanceId/runId must never resolve.
 */
fun resolveNeedsYouDeepLink(
    pending: NeedsYouDeepLink?,
    pairedInstanceIds: Set<String>,
): NeedsYouDeepLink? {
    val link = pending ?: return null
    if (link.instanceId.isBlank() || link.runId.isBlank()) return null
    if (link.instanceId !in pairedInstanceIds) return null
    return link
}

/**
 * Process-wide pending slot so a notification tap can land before the
 * Chidori ViewModel's paired list is ready (and so phone Fragment vs tablet
 * detail ViewModels share one queue).
 */
internal object NeedsYouPendingHold {
    @Volatile
    private var pending: NeedsYouDeepLink? = null

    /**
     * Set by the Chidori screen while it is started, so a notification tap
     * that arrives after onStart still opens the run. Cleared when that
     * screen stops.
     */
    @Volatile
    var onOffered: (() -> Unit)? = null

    fun offer(link: NeedsYouDeepLink) {
        pending = link
        onOffered?.invoke()
    }

    fun peek(): NeedsYouDeepLink? = pending

    /**
     * If [pairedInstanceIds] can resolve the held link, clear and return it.
     * Otherwise leave the pending value untouched and return null.
     */
    fun takeIfResolved(pairedInstanceIds: Set<String>): NeedsYouDeepLink? {
        val resolved = resolveNeedsYouDeepLink(pending, pairedInstanceIds) ?: return null
        pending = null
        return resolved
    }
}
