package com.druk.lmplayground.checkin

import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.team.TeamHandoff
import com.druk.lmplayground.team.TeamRoomSession
import java.util.UUID

/**
 * Runs already fetched for this check-in. [failedInstanceIds] were not
 * reachable; their items must not be marked DONE.
 */
class DesktopProbeCache {
    var runs: Map<String, List<AgentRunSummary>> = emptyMap()
    var failedInstanceIds: Set<String> = emptySet()
}

/**
 * Paired-desktop capture. NEEDS_YOU and RUNNING become items. COMPLETED and
 * FAILED mark a matching row DONE. A failed list call is skipped.
 */
object DesktopRunIngest {
    suspend fun ingest(cache: DesktopProbeCache, writer: OpenItemWriter, nowMs: Long) {
        for ((instanceId, runs) in cache.runs) {
            for (run in runs) {
                when (run.state) {
                    AgentRunState.COMPLETED, AgentRunState.FAILED ->
                        writer.markResolvedBySource(
                            OpenItemSource.DESKTOP_RUN.storageValue(),
                            run.runId,
                            "run ${run.state.name.lowercase()}",
                            nowMs,
                        )
                    AgentRunState.NEEDS_YOU, AgentRunState.RUNNING ->
                        writer.upsertCaptured(desktopItem(instanceId, run, nowMs))
                }
            }
        }
    }

    fun desktopItem(instanceId: String, run: AgentRunSummary, nowMs: Long): OpenItemEntity {
        val needsYou = run.state == AgentRunState.NEEDS_YOU
        val kind = if (needsYou) OpenItemKind.WAITING_ON_USER else OpenItemKind.HALF_DONE
        val summary = buildString {
            append(if (needsYou) "Desktop run needs you" else "Desktop run still going")
            append(": ")
            append(run.runId)
            val detail = run.reason ?: run.currentStep
            if (!detail.isNullOrBlank()) {
                append(" — ")
                append(detail)
            }
        }.take(280)
        val identity = "run:${run.runId}"
        val fingerprint = openItemFingerprint(
            OpenItemSource.DESKTOP_RUN.storageValue(),
            run.runId,
            kind.name,
            identity,
        )
        val state = if (needsYou) {
            "needs_you:" + (run.reason ?: "")
        } else {
            "running:" + (run.currentStep ?: "")
        }
        return OpenItemEntity(
            id = UUID.randomUUID().toString(),
            source = OpenItemSource.DESKTOP_RUN.storageValue(),
            sourceId = run.runId,
            kind = kind.name,
            summary = summary,
            probeJson = ProbeDescriptor(
                type = ProbeDescriptor.TYPE_DESKTOP_RUN,
                instanceId = instanceId,
                runId = run.runId,
            ).toJson(),
            status = OpenItemStatus.OPEN.name,
            lastProbeState = state,
            initialProbeState = state,
            fingerprint = fingerprint,
            fresh = true,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }
}

/**
 * Team-room capture. A handoff is unanswered until a later handoff is sent
 * by its target role. The reply marks the earlier row DONE.
 */
object TeamHandoffIngest {
    suspend fun onHandoff(handoff: TeamHandoff, room: TeamRoomSession, writer: OpenItemWriter, nowMs: Long) {
        resolveAnswered(room, writer, nowMs)
        if (isUnanswered(handoff, room)) {
            writer.upsertCaptured(itemFor(handoff, nowMs))
        }
    }

    suspend fun syncRoom(room: TeamRoomSession, writer: OpenItemWriter, nowMs: Long) {
        resolveAnswered(room, writer, nowMs)
        for (handoff in room.handoffs()) {
            if (isUnanswered(handoff, room)) {
                writer.upsertCaptured(itemFor(handoff, nowMs))
            }
        }
    }

    fun isUnanswered(handoff: TeamHandoff, room: TeamRoomSession): Boolean {
        val all = room.handoffs()
        val index = all.indexOfLast { it == handoff }
        if (index < 0) return true
        return all.drop(index + 1).none { it.fromRole == handoff.toRole }
    }

    private suspend fun resolveAnswered(room: TeamRoomSession, writer: OpenItemWriter, nowMs: Long) {
        val all = room.handoffs()
        for ((index, handoff) in all.withIndex()) {
            val answered = all.drop(index + 1).any { it.fromRole == handoff.toRole }
            if (!answered) continue
            writer.markResolvedBySource(
                OpenItemSource.TEAM_ROOM.storageValue(),
                sourceId(handoff),
                "handoff answered by ${handoff.toRole.name}",
                nowMs,
            )
        }
    }

    fun itemFor(handoff: TeamHandoff, nowMs: Long): OpenItemEntity {
        val sourceId = sourceId(handoff)
        val summary = "${handoff.fromRole.mentionName} → ${handoff.toRole.mentionName}: ${handoff.body}"
            .take(280)
        val fingerprint = openItemFingerprint(
            OpenItemSource.TEAM_ROOM.storageValue(),
            sourceId,
            OpenItemKind.WAITING_ON_USER.name,
            summary,
        )
        return OpenItemEntity(
            id = UUID.randomUUID().toString(),
            source = OpenItemSource.TEAM_ROOM.storageValue(),
            sourceId = sourceId,
            kind = OpenItemKind.WAITING_ON_USER.name,
            summary = summary.ifBlank { "Unanswered team handoff" },
            probeJson = ProbeDescriptor(
                type = ProbeDescriptor.TYPE_TEAM_HANDOFF,
                fromRole = handoff.fromRole.name,
                toRole = handoff.toRole.name,
                handoffKey = sourceId,
            ).toJson(),
            status = OpenItemStatus.OPEN.name,
            lastProbeState = "unanswered",
            initialProbeState = "unanswered",
            fingerprint = fingerprint,
            fresh = true,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }

    fun sourceId(handoff: TeamHandoff): String =
        openItemFingerprint(
            "team_handoff",
            "${handoff.fromRole.name}->${handoff.toRole.name}",
            handoff.priority.toString(),
            handoff.body,
        )
}
