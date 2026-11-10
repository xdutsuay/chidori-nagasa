package com.druk.lmplayground.team

/**
 * In-memory CP8 team room: default Planner / Worker / Critic with status chips
 * and structured handoffs. Single-GGUF orchestration wires later; this is the
 * coordination model the UI and Critic gate share.
 */
class TeamRoomSession(
    roles: List<TeamRole> = listOf(TeamRole.PLANNER, TeamRole.WORKER, TeamRole.CRITIC),
) {
    private val status = LinkedHashMap<TeamRole, TeamRoleStatus>().apply {
        for (r in roles.distinct()) put(r, TeamRoleStatus.IDLE)
    }
    private val handoffs = mutableListOf<TeamHandoff>()

    fun roles(): List<TeamRole> = status.keys.toList()

    fun statusOf(role: TeamRole): TeamRoleStatus =
        status[role] ?: TeamRoleStatus.IDLE

    fun setRoleStatus(role: TeamRole, value: TeamRoleStatus) {
        if (role !in status) return
        status[role] = value
    }

    fun addHandoff(handoff: TeamHandoff) {
        handoffs.add(handoff)
        TeamRoomRegistry.current = this
        handoffListener?.invoke(handoff)
        TeamHandoffEvents.listener?.invoke(this, handoff)
    }

    /**
     * Optional per-session listener. [TeamHandoffEvents] is the process-wide
     * sink check-ins install so a handoff is captured without the room
     * knowing about the ledger.
     */
    var handoffListener: ((TeamHandoff) -> Unit)? = null

    fun handoffs(): List<TeamHandoff> = handoffs.toList()

    companion object {
        fun createDefault(): TeamRoomSession = TeamRoomSession()
    }
}

/**
 * The room most recently handed off in this process, if it is still alive.
 * Check-in probes read it. There is no durable team-room store on this baseline.
 */
object TeamRoomRegistry {
    @Volatile
    var current: TeamRoomSession? = null
}

/**
 * Process-wide handoff sink. Check-ins register a listener from [com.druk.lmplayground.App].
 * The team package does not depend on the check-in ledger.
 */
object TeamHandoffEvents {
    @Volatile
    var listener: ((TeamRoomSession, TeamHandoff) -> Unit)? = null
}
