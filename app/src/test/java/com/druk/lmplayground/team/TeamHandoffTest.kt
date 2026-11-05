package com.druk.lmplayground.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CP8 tests for the structured in-room handoff message (feature E seed).
 *
 * Production symbols exercised (package com.druk.lmplayground.team):
 *  - data class TeamHandoff(fromRole: TeamRole, toRole: TeamRole, priority: Int, body: String)
 *      with fun toJson(): String and companion fun fromJson(json: String): TeamHandoff?
 *
 * priority is Int, higher-first (1 = urgent), per TeamHandoff's contract.
 * Robolectric is required only because toJson/fromJson use org.json, which is
 * stubbed on the plain JVM test classpath (repo precedent: ModelInfoTest).
 */
@RunWith(RobolectricTestRunner::class)
class TeamHandoffTest {

    @Test
    fun `handoff keeps from to priority and body`() {
        val handoff = TeamHandoff(
            fromRole = TeamRole.PLANNER,
            toRole = TeamRole.WORKER,
            priority = 1,
            body = "Implement the tokenizer fix first",
        )
        assertEquals(TeamRole.PLANNER, handoff.fromRole)
        assertEquals(TeamRole.WORKER, handoff.toRole)
        assertEquals(1, handoff.priority)
        assertEquals("Implement the tokenizer fix first", handoff.body)
    }

    @Test
    fun `json round trip preserves all fields`() {
        val original = TeamHandoff(
            fromRole = TeamRole.CRITIC,
            toRole = TeamRole.PLANNER,
            priority = 3,
            body = "Checklist item 3 is ungrounded",
        )
        assertEquals(original, TeamHandoff.fromJson(original.toJson()))
    }

    @Test
    fun `json contains the handoff keys`() {
        val json = TeamHandoff(
            fromRole = TeamRole.PLANNER,
            toRole = TeamRole.WORKER,
            priority = 1,
            body = "go",
        ).toJson()
        assertTrue(json.contains("from"))
        assertTrue(json.contains("to"))
        assertTrue(json.contains("priority"))
        assertTrue(json.contains("body"))
    }

    @Test
    fun `json body survives quotes and newlines`() {
        val body = "line1\n\"quoted\" \\path"
        val original = TeamHandoff(
            fromRole = TeamRole.WORKER,
            toRole = TeamRole.CRITIC,
            priority = 2,
            body = body,
        )
        assertEquals(body, TeamHandoff.fromJson(original.toJson())?.body)
    }

    @Test
    fun `fromJson of invalid json returns null`() {
        assertNull(TeamHandoff.fromJson("not json at all"))
        assertNull(TeamHandoff.fromJson(""))
    }

    @Test
    fun `fromJson with unknown role names returns null`() {
        assertNull(
            TeamHandoff.fromJson(
                """{"from":"NOBODY","to":"CRITIC","priority":1,"body":"x"}""",
            ),
        )
    }

    @Test
    fun `fromJson uses default priority when missing`() {
        val decoded = TeamHandoff.fromJson(
            """{"from":"PLANNER","to":"WORKER","body":"no priority given"}""",
        )
        assertEquals(0, decoded?.priority)
    }
}
