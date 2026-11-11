package com.xdutsuay.chidori.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP8 tests for the team-room role model and @mention parsing.
 *
 * Production symbols exercised (package com.xdutsuay.chidori.team):
 *  - enum TeamRole { PLANNER, WORKER, CRITIC } with mentionName: String
 *  - TeamRole.fromMention(token: String): TeamRole?
 *  - object TeamMentions.parseMentions(text: String): List<TeamRole>
 */
class TeamRoleTest {

    @Test
    fun `role enum has planner worker critic with mention names`() {
        assertEquals("Planner", TeamRole.PLANNER.mentionName)
        assertEquals("Worker", TeamRole.WORKER.mentionName)
        assertEquals("Critic", TeamRole.CRITIC.mentionName)
        assertEquals(3, TeamRole.entries.size)
    }

    @Test
    fun `fromMention is case insensitive and ignores leading at`() {
        assertEquals(TeamRole.PLANNER, TeamRole.fromMention("@planner"))
        assertEquals(TeamRole.WORKER, TeamRole.fromMention("WORKER"))
        assertEquals(TeamRole.CRITIC, TeamRole.fromMention("@CrItIc"))
        assertNull(TeamRole.fromMention("@Nobody"))
    }

    @Test
    fun `parseMentions finds all three roles in one message`() {
        val mentions = TeamMentions.parseMentions("@Planner split the task, @Worker run it, @Critic review")
        assertEquals(listOf(TeamRole.PLANNER, TeamRole.WORKER, TeamRole.CRITIC), mentions)
    }

    @Test
    fun `parseMentions is case insensitive`() {
        val mentions = TeamMentions.parseMentions("hey @planner and @WORKER, what about @CrItIc")
        assertEquals(
            listOf(TeamRole.PLANNER, TeamRole.WORKER, TeamRole.CRITIC),
            mentions,
        )
    }

    @Test
    fun `parseMentions returns each role once even if repeated`() {
        val mentions = TeamMentions.parseMentions("@Worker do it @Worker again @Worker")
        assertEquals(listOf(TeamRole.WORKER), mentions)
    }

    @Test
    fun `parseMentions preserves order of first appearance`() {
        val mentions = TeamMentions.parseMentions("@Critic first then @Planner then @Critic again")
        assertEquals(listOf(TeamRole.CRITIC, TeamRole.PLANNER), mentions)
    }

    @Test
    fun `parseMentions empty text returns empty list`() {
        assertEquals(emptyList<TeamRole>(), TeamMentions.parseMentions(""))
    }

    @Test
    fun `parseMentions blank text returns empty list`() {
        assertTrue(TeamMentions.parseMentions("   \n\t ").isEmpty())
    }

    @Test
    fun `parseMentions no mentions returns empty list`() {
        assertTrue(TeamMentions.parseMentions("plain message without any roles").isEmpty())
    }

    @Test
    fun `parseMentions requires exact role match so Plannerism is not Planner`() {
        assertEquals(emptyList<TeamRole>(), TeamMentions.parseMentions("the @Plannerism is strong"))
    }

    @Test
    fun `parseMentions matches mid-sentence mention`() {
        assertEquals(listOf(TeamRole.CRITIC), TeamMentions.parseMentions("ok @Critic, go ahead"))
    }

    @Test
    fun `parseMentions matches mention at start of text`() {
        assertEquals(listOf(TeamRole.PLANNER), TeamMentions.parseMentions("@Planner please plan"))
    }

    @Test
    fun `parseMentions matches mention wrapped in parens`() {
        assertEquals(listOf(TeamRole.WORKER), TeamMentions.parseMentions("route to (@Worker) next"))
    }
}
