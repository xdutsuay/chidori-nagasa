package com.druk.lmplayground.checkin

import com.druk.lmplayground.coordinator.model.AgentMode
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.team.TeamHandoff
import com.druk.lmplayground.team.TeamRole
import com.druk.lmplayground.team.TeamRoomSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIngestTest {
    @Test
    fun desktopNeedsYouAndRunningUpsertAndCompletedResolves() = runBlocking {
        val writer = MemWriter()
        val cache = DesktopProbeCache()
        cache.runs = mapOf(
            "desk-1" to listOf(
                run("r-need", AgentRunState.NEEDS_YOU, reason = "approve the edit"),
                run("r-run", AgentRunState.RUNNING, step = "building"),
                run("r-done", AgentRunState.COMPLETED),
            ),
        )
        cache.failedInstanceIds = setOf("desk-offline")
        DesktopRunIngest.ingest(cache, writer, nowMs = 50)
        assertEquals(listOf("r-need", "r-run"), writer.items.map { it.sourceId })
        assertTrue(writer.items.all { it.fresh && it.source == "desktop_run" })
        assertEquals(listOf("r-done"), writer.resolved)
        assertTrue(writer.resolved.none { it == "desk-offline" })
    }

    @Test
    fun failedListDoesNotResolve() = runBlocking {
        val writer = MemWriter()
        val cache = DesktopProbeCache()
        cache.failedInstanceIds = setOf("desk-1")
        DesktopRunIngest.ingest(cache, writer, nowMs = 1)
        assertTrue(writer.items.isEmpty())
        assertTrue(writer.resolved.isEmpty())
    }

    @Test
    fun unansweredHandoffUpsertsAndAReplyResolvesIt() = runBlocking {
        val room = TeamRoomSession.createDefault()
        val writer = MemWriter()
        val first = TeamHandoff(TeamRole.PLANNER, TeamRole.WORKER, 1, "draft the note")
        room.addHandoff(first)
        TeamHandoffIngest.onHandoff(first, room, writer, 10)
        assertEquals(1, writer.items.size)
        assertEquals("team_room", writer.items.single().source)
        assertTrue(TeamHandoffIngest.isUnanswered(first, room))

        val reply = TeamHandoff(TeamRole.WORKER, TeamRole.CRITIC, 1, "draft is ready")
        room.addHandoff(reply)
        TeamHandoffIngest.onHandoff(reply, room, writer, 20)
        assertEquals(TeamHandoffIngest.sourceId(first), writer.resolved.single())
        assertTrue(writer.items.any { it.sourceId == TeamHandoffIngest.sourceId(reply) })
        assertTrue(!TeamHandoffIngest.isUnanswered(first, room))
    }

    @Test
    fun downloadProbeResolvesOnlyAKnownCompleteFile() {
        val item = OpenItemEntity(
            id = "d",
            source = "chat",
            sourceId = "d",
            kind = "HALF_DONE",
            summary = "model",
            probeJson = ProbeDescriptor(type = ProbeDescriptor.TYPE_DOWNLOAD, downloadFilename = "model.gguf").toJson(),
            status = "OPEN",
            lastProbeState = "unknown",
            initialProbeState = "unknown",
            fingerprint = "fp",
            createdAt = 1,
            updatedAt = 1,
        )
        val waiting = CheckInProber.probe(listOf(item), 10, emptyMap(), emptySet(), null)
        assertTrue(!waiting.getValue("d").resolved)
        val done = CheckInProber.probe(listOf(item), 10, emptyMap(), emptySet(), null, setOf("model.gguf"))
        assertTrue(done.getValue("d").resolved)
    }

    private fun run(
        id: String,
        state: AgentRunState,
        reason: String? = null,
        step: String? = null,
    ) = AgentRunSummary(
        runId = id,
        mode = AgentMode.AGENT,
        startedAtEpochMillis = 1,
        state = state,
        currentStep = step,
        reason = reason,
    )
}

private class MemWriter : OpenItemWriter {
    val items = mutableListOf<OpenItemEntity>()
    val resolved = mutableListOf<String>()
    override suspend fun upsertCaptured(item: OpenItemEntity) {
        items += item
    }
    override suspend fun markResolvedBySource(source: String, sourceId: String, reason: String, nowMs: Long) {
        resolved += sourceId
    }
}
