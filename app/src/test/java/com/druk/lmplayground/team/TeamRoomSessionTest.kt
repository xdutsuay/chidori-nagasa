package com.druk.lmplayground.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP8 tests for the in-memory team room session.
 *
 * Production symbols exercised (package com.druk.lmplayground.team):
 *  - enum TeamRoleStatus { IDLE, THINKING, TOOL, WAITING_ON_YOU }
 *  - class TeamRoomSession(roles: List<TeamRole> = default) with:
 *      fun roles(): List<TeamRole>
 *      fun statusOf(role: TeamRole): TeamRoleStatus
 *      fun setRoleStatus(role: TeamRole, value: TeamRoleStatus)
 *      fun addHandoff(handoff: TeamHandoff) / fun handoffs(): List<TeamHandoff>
 *      companion fun createDefault(): TeamRoomSession
 */
class TeamRoomSessionTest {

    @Test
    fun `default room has exactly planner worker critic`() {
        val room = TeamRoomSession.createDefault()
        assertEquals(listOf(TeamRole.PLANNER, TeamRole.WORKER, TeamRole.CRITIC), room.roles())
    }

    @Test
    fun `every role starts idle`() {
        val room = TeamRoomSession.createDefault()
        for (role in room.roles()) {
            assertEquals(TeamRoleStatus.IDLE, room.statusOf(role))
        }
    }

    @Test
    fun `setRoleStatus transitions a single role without touching others`() {
        val room = TeamRoomSession.createDefault()
        room.setRoleStatus(TeamRole.WORKER, TeamRoleStatus.THINKING)
        assertEquals(TeamRoleStatus.THINKING, room.statusOf(TeamRole.WORKER))
        assertEquals(TeamRoleStatus.IDLE, room.statusOf(TeamRole.PLANNER))
        assertEquals(TeamRoleStatus.IDLE, room.statusOf(TeamRole.CRITIC))
    }

    @Test
    fun `worker can move thinking to tool and then back to idle`() {
        val room = TeamRoomSession.createDefault()
        room.setRoleStatus(TeamRole.WORKER, TeamRoleStatus.TOOL)
        assertEquals(TeamRoleStatus.TOOL, room.statusOf(TeamRole.WORKER))
        room.setRoleStatus(TeamRole.WORKER, TeamRoleStatus.IDLE)
        assertEquals(TeamRoleStatus.IDLE, room.statusOf(TeamRole.WORKER))
    }

    @Test
    fun `planner can wait on user`() {
        val room = TeamRoomSession.createDefault()
        room.setRoleStatus(TeamRole.PLANNER, TeamRoleStatus.WAITING_ON_YOU)
        assertEquals(TeamRoleStatus.WAITING_ON_YOU, room.statusOf(TeamRole.PLANNER))
    }

    @Test
    fun `status of a role outside the room falls back to idle`() {
        val room = TeamRoomSession(roles = listOf(TeamRole.PLANNER, TeamRole.WORKER))
        // CRITIC is not in this room; session must not crash and must report IDLE.
        assertEquals(TeamRoleStatus.IDLE, room.statusOf(TeamRole.CRITIC))
        room.setRoleStatus(TeamRole.CRITIC, TeamRoleStatus.THINKING)
        assertEquals(TeamRoleStatus.IDLE, room.statusOf(TeamRole.CRITIC))
    }

    @Test
    fun `custom room supports a two agent minimum`() {
        val room = TeamRoomSession(roles = listOf(TeamRole.PLANNER, TeamRole.WORKER))
        assertEquals(2, room.roles().size)
        assertTrue(room.roles().containsAll(listOf(TeamRole.PLANNER, TeamRole.WORKER)))
    }

    @Test
    fun `handoff log starts empty and appends in order`() {
        val room = TeamRoomSession.createDefault()
        assertTrue(room.handoffs().isEmpty())

        val first = TeamHandoff(
            fromRole = TeamRole.PLANNER,
            toRole = TeamRole.WORKER,
            priority = 1,
            body = "task one",
        )
        val second = TeamHandoff(
            fromRole = TeamRole.WORKER,
            toRole = TeamRole.CRITIC,
            priority = 2,
            body = "for review",
        )
        room.addHandoff(first)
        room.addHandoff(second)
        assertEquals(listOf(first, second), room.handoffs())
    }
}
