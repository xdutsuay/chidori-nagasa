package com.druk.lmplayground.team

/**
 * Named agents in a CP8 team room. Planner has no tools; Worker may use tools;
 * Critic reviews tool rows / checklist before "done".
 */
enum class TeamRole {
    PLANNER,
    WORKER,
    CRITIC,
    ;

    /** @-mention token without the leading '@' (e.g. Planner). */
    val mentionName: String
        get() = when (this) {
            PLANNER -> "Planner"
            WORKER -> "Worker"
            CRITIC -> "Critic"
        }

    companion object {
        fun fromMention(token: String): TeamRole? {
            val t = token.trim().removePrefix("@")
            return entries.firstOrNull { it.mentionName.equals(t, ignoreCase = true) }
        }
    }
}

/** Per-role activity chip in the room UI. */
enum class TeamRoleStatus {
    IDLE,
    THINKING,
    TOOL,
    WAITING_ON_YOU,
}
