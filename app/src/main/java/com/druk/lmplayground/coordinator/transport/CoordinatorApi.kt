package com.druk.lmplayground.coordinator.transport

import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ProtocolVersion
import com.druk.lmplayground.coordinator.model.RemoteChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * The actual HTTP(S)/WebSocket client against one paired `lclreason`
 * instance (protocol §2.1, §2.3, §2.4). This is client-mode only — node
 * mode (protocol §2.5) has its own registration surface, see
 * `coordinator.node`.
 *
 * Every call here is a live wire-contract usage: if you're changing a
 * method signature in a way that changes what goes over the network,
 * that's a CHIDORI_PROTOCOL.md §2 change and needs the notify/acknowledge
 * process in §1.3.
 */
interface CoordinatorApi {
    fun observeConnectionState(instanceId: InstanceId): Flow<CoordinatorConnectionState>

    suspend fun negotiateProtocolVersion(instanceId: InstanceId, clientVersion: ProtocolVersion): ProtocolVersion

    fun observeStatus(instanceId: InstanceId): Flow<CoordinatorStatus>

    suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary>

    suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail

    fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage>

    suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String)
}

/**
 * OkHttp-backed implementation (this app already depends on okhttp3 for
 * downloads — no new networking dependency needed for this module).
 *
 * Stub — every method is unimplemented. Do not fill these in before
 * ROADMAP.md Phase 1 (the joint wire-contract spec session with lclreason)
 * has actually pinned down lclreason's internal/api surface; guessing the
 * endpoint shapes here would just create a contract we'd have to break
 * later.
 */
class OkHttpCoordinatorApi : CoordinatorApi {
    override fun observeConnectionState(instanceId: InstanceId): Flow<CoordinatorConnectionState> {
        TODO("Not implemented — see ROADMAP.md Phase 1/2.")
    }

    override suspend fun negotiateProtocolVersion(
        instanceId: InstanceId,
        clientVersion: ProtocolVersion,
    ): ProtocolVersion {
        TODO("Not implemented — see ROADMAP.md Phase 1. Must degrade gracefully per protocol §2.3, never crash on an unrecognized version.")
    }

    override fun observeStatus(instanceId: InstanceId): Flow<CoordinatorStatus> {
        TODO("Not implemented — see ROADMAP.md Phase 2.")
    }

    override suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary> {
        TODO("Not implemented — see ROADMAP.md Phase 2.")
    }

    override suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail {
        TODO("Not implemented — see ROADMAP.md Phase 2.")
    }

    override fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage> {
        TODO("Not implemented — see ROADMAP.md Phase 3.")
    }

    override suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String) {
        TODO("Not implemented — see ROADMAP.md Phase 3.")
    }
}
