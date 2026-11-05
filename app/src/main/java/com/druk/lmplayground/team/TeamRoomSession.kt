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
    }

    fun handoffs(): List<TeamHandoff> = handoffs.toList()

    companion object {
        fun createDefault(): TeamRoomSession = TeamRoomSession()
    }
}
