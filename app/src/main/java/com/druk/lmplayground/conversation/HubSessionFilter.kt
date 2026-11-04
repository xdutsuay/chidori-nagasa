package com.druk.lmplayground.conversation

import com.druk.lmplayground.data.ChatRoute
import com.druk.lmplayground.data.ChatSessionEntity

/** Route chips in the hub drawer (session list). */
enum class HubSessionFilter {
    ALL,
    ON_DEVICE,
    /** Desktop remote chats live in Chidori monitor — chip shows CTA, not rows. */
    DESKTOP,
    CLOUD,
}

/**
 * Filter chat sessions for the hub drawer route chips + optional title search.
 * ALL → order preserved; ON_DEVICE → not CLOUD (blank/unknown = on-device);
 * DESKTOP → empty (open Chidori Desktop); CLOUD → CLOUD only.
 * [query] matches [ChatSessionEntity.title] case-insensitively when non-blank.
 */
fun filterSessionsForHub(
    sessions: List<ChatSessionEntity>,
    filter: HubSessionFilter,
    query: String = "",
): List<ChatSessionEntity> {
    val byRoute = when (filter) {
        HubSessionFilter.ALL -> sessions
        HubSessionFilter.ON_DEVICE -> sessions.filter { it.route != ChatRoute.CLOUD }
        HubSessionFilter.DESKTOP -> emptyList()
        HubSessionFilter.CLOUD -> sessions.filter { it.route == ChatRoute.CLOUD }
    }
    val q = query.trim()
    if (q.isEmpty()) return byRoute
    return byRoute.filter { it.title.contains(q, ignoreCase = true) }
}

/**
 * Format a needs_you badge count for the drawer destination row.
 * 0 / negative → null (no badge); 1..99 → digits; 100+ → "99+".
 */
fun formatNeedsYouBadge(count: Int): String? = when {
    count <= 0 -> null
    count <= 99 -> count.toString()
    else -> "99+"
}

/** Compact usage line for the drawer footer (today's local aggregate). */
fun formatUsageDrawerFooter(requests: Int, totalTokens: Long): String {
    val req = requests.coerceAtLeast(0)
    val tokens = totalTokens.coerceAtLeast(0L)
    return if (req == 0) {
        "No usage today"
    } else {
        "$req requests · ${formatCompactTokens(tokens)} tokens"
    }
}

internal fun formatCompactTokens(tokens: Long): String = when {
    tokens < 1_000L -> tokens.toString()
    tokens < 1_000_000L -> String.format("%.1fk", tokens / 1_000.0).trimEnd('0').trimEnd('.')
    else -> String.format("%.1fM", tokens / 1_000_000.0).trimEnd('0').trimEnd('.')
}
