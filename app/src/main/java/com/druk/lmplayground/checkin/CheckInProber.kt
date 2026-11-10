package com.druk.lmplayground.checkin

import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.team.TeamRoomSession

/**
 * Live re-probe used by gate 4. A missing desktop or a dead team room is
 * "unknown", not resolved: we only mark DONE when the source says the work
 * finished. Download filenames in [completedDownloads] resolve a download
 * probe. COS jobs have no local ledger on this baseline, so that probe
 * stays unresolved.
 */
object CheckInProber {
    fun probe(
        items: List<OpenItemEntity>,
        nowMs: Long,
        runsByInstance: Map<String, List<AgentRunSummary>>,
        failedInstanceIds: Set<String>,
        room: TeamRoomSession?,
        completedDownloads: Set<String> = emptySet(),
    ): Map<String, ProbeSnapshot> {
        val out = LinkedHashMap<String, ProbeSnapshot>(items.size)
        for (item in items) {
            val descriptor = ProbeDescriptor.fromJson(item.probeJson)
            out[item.id] = when (descriptor?.type) {
                ProbeDescriptor.TYPE_DESKTOP_RUN -> desktop(descriptor, runsByInstance, failedInstanceIds)
                ProbeDescriptor.TYPE_TEAM_HANDOFF -> team(descriptor, room)
                ProbeDescriptor.TYPE_DOWNLOAD -> download(descriptor, completedDownloads)
                ProbeDescriptor.TYPE_DUE_DATE -> due(item, nowMs)
                ProbeDescriptor.TYPE_COS_JOB -> ProbeSnapshot(state = "unknown", resolved = false)
                else -> ProbeSnapshot(state = item.initialProbeState.ifBlank { "open" }, resolved = false)
            }
        }
        return out
    }

    private fun desktop(
        descriptor: ProbeDescriptor,
        runsByInstance: Map<String, List<AgentRunSummary>>,
        failedInstanceIds: Set<String>,
    ): ProbeSnapshot {
        val instanceId = descriptor.instanceId
        val runId = descriptor.runId
        if (instanceId.isNullOrBlank() || runId.isNullOrBlank()) {
            return ProbeSnapshot(state = "unknown", resolved = false)
        }
        if (instanceId in failedInstanceIds || instanceId !in runsByInstance) {
            return ProbeSnapshot(state = "unknown", resolved = false)
        }
        val run = runsByInstance[instanceId].orEmpty().firstOrNull { it.runId == runId }
            ?: return ProbeSnapshot(state = "missing", resolved = false)
        return when (run.state) {
            AgentRunState.COMPLETED ->
                ProbeSnapshot(state = "completed", resolved = true, reason = "run completed")
            AgentRunState.FAILED ->
                ProbeSnapshot(state = "failed", resolved = true, reason = "run failed")
            AgentRunState.NEEDS_YOU ->
                ProbeSnapshot(state = "needs_you:" + (run.reason ?: ""), resolved = false)
            AgentRunState.RUNNING ->
                ProbeSnapshot(state = "running:" + (run.currentStep ?: ""), resolved = false)
        }
    }

    private fun team(descriptor: ProbeDescriptor, room: TeamRoomSession?): ProbeSnapshot {
        if (room == null) return ProbeSnapshot(state = "unanswered", resolved = false)
        val toRole = descriptor.toRole
        if (toRole.isNullOrBlank()) return ProbeSnapshot(state = "unanswered", resolved = false)
        val key = descriptor.handoffKey
        val handoffs = room.handoffs()
        val index = handoffs.indexOfFirst { TeamHandoffIngest.sourceId(it) == key }
        if (index < 0) return ProbeSnapshot(state = "unanswered", resolved = false)
        val answered = handoffs.drop(index + 1).any { it.fromRole.name == toRole }
        return if (answered) {
            ProbeSnapshot(state = "answered", resolved = true, reason = "handoff answered")
        } else {
            ProbeSnapshot(state = "unanswered", resolved = false)
        }
    }

    private fun download(descriptor: ProbeDescriptor, completedDownloads: Set<String>): ProbeSnapshot {
        val name = descriptor.downloadFilename
        if (!name.isNullOrBlank() && name in completedDownloads) {
            return ProbeSnapshot(state = "complete", resolved = true, reason = "download complete")
        }
        return ProbeSnapshot(state = "unknown", resolved = false)
    }

    private fun due(item: OpenItemEntity, nowMs: Long): ProbeSnapshot {
        val dueAt = item.dueAt
        val state = when {
            dueAt == null -> "scheduled"
            dueAt <= nowMs -> "overdue"
            else -> "scheduled"
        }
        return ProbeSnapshot(state = state, resolved = false)
    }
}
