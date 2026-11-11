package com.xdutsuay.chidori.coordinator

import android.content.Context
import com.xdutsuay.chidori.coordinator.discovery.InstanceDiscovery
import com.xdutsuay.chidori.coordinator.discovery.NsdInstanceDiscovery
import com.xdutsuay.chidori.coordinator.model.AgentRunDetail
import com.xdutsuay.chidori.coordinator.model.AgentRunSummary
import com.xdutsuay.chidori.coordinator.model.ApplyEditsRequest
import com.xdutsuay.chidori.coordinator.model.ApplyEditsResult
import com.xdutsuay.chidori.coordinator.model.CoordinatorConnectionState
import com.xdutsuay.chidori.coordinator.model.CoordinatorStatusInfo
import com.xdutsuay.chidori.coordinator.model.DiscoveredInstance
import com.xdutsuay.chidori.coordinator.model.InstanceId
import com.xdutsuay.chidori.coordinator.model.NotificationsPage
import com.xdutsuay.chidori.coordinator.model.ManualEndpoint
import com.xdutsuay.chidori.coordinator.model.PairedInstance
import com.xdutsuay.chidori.coordinator.model.RemoteChatMessage
import com.xdutsuay.chidori.coordinator.node.DefaultNodeRegistrationCapability
import com.xdutsuay.chidori.coordinator.node.NodeRegistrationCapability
import com.xdutsuay.chidori.coordinator.pairing.PairedInstanceStore
import com.xdutsuay.chidori.coordinator.pairing.PairingManager
import com.xdutsuay.chidori.coordinator.pairing.PairingManagerImpl
import com.xdutsuay.chidori.coordinator.pairing.RoomPairedInstanceStore
import com.xdutsuay.chidori.coordinator.pairing.data.CoordinatorDatabase
import com.xdutsuay.chidori.coordinator.transport.CoordinatorApi
import com.xdutsuay.chidori.coordinator.transport.OkHttpCoordinatorApi
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for everything in `net/coordinator` (protocol §3.4:
 * "UI code never constructs coordinator requests directly"). UI/ViewModel
 * code should only ever talk to this class, never to `discovery`,
 * `pairing`, `transport`, or `node` types directly.
 *
 * Backed by real (not stub) discovery/pairing/transport implementations as
 * of this draft, written against WIRE_CONTRACT.md — an unreconciled draft
 * of the actual `lclreason` API (see that file). This is not build-verified
 * in the environment it was written in (no Android/NDK toolchain
 * available); confirm it compiles and exercise it against a real
 * `lclreason` instance before trusting it, per CHIDORI_PROTOCOL.md §3.1's
 * merge gates.
 */
class CoordinatorRepository(context: Context) {

    private val appContext = context.applicationContext

    private val discovery: InstanceDiscovery = NsdInstanceDiscovery(appContext)

    private val pairedInstanceStore: PairedInstanceStore = RoomPairedInstanceStore(
        CoordinatorDatabase.getInstance(appContext).pairedInstanceDao()
    )

    private val api: CoordinatorApi = OkHttpCoordinatorApi(
        authTokenProvider = { instanceId -> pairedInstanceStore.getAuthToken(instanceId) },
        endpointProvider = { instanceId ->
            pairedInstanceStore.get(instanceId)?.let { it.lastKnownHost to it.lastKnownPort }
        },
    )

    val pairingManager: PairingManager = PairingManagerImpl(pairedInstanceStore, api)

    val nodeRegistration: NodeRegistrationCapability =
        DefaultNodeRegistrationCapability(appContext, api, pairedInstanceStore)

    fun observeDiscoveredInstances(): Flow<List<DiscoveredInstance>> =
        discovery.observeDiscoveredInstances()

    fun startDiscovery() = discovery.startDiscovery()

    fun stopDiscovery() = discovery.stopDiscovery()

    fun observePairedInstances(): Flow<List<PairedInstance>> = pairingManager.observePairedInstances()

    /**
     * Manual host:port fallback per protocol §2.1 — always available, not
     * gated behind a "troubleshooting" menu (PRD.md §6.2). Wraps the target
     * as a synthetic [DiscoveredInstance] with an unknown instanceId; the
     * real instanceId is only known once `/version` or `/pairing/begin`
     * responds, so callers should treat this as a pairing *candidate*, not
     * a resolved instance.
     */
    fun manualEndpoint(host: String, port: Int): ManualEndpoint = ManualEndpoint(host, port)

    // Coordinator status/run monitor (protocol §2.4, PRD.md §6.3). Plain
    // suspend pass-throughs to CoordinatorApi rather than Flows — the
    // monitor ViewModel polls these on an interval (no server push/WS for
    // status/runs in WIRE_CONTRACT.md's v1 draft; only remote chat uses a
    // live socket, and that's still Phase 3). Exposed here, not from
    // `transport` directly, per protocol §3.4.
    suspend fun getStatus(instanceId: InstanceId): CoordinatorStatusInfo = api.getStatus(instanceId)

    suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary> = api.listRuns(instanceId)

    /** Companion long-poll for run-state events (KMA-288). Plain suspend
     * pass-through to CoordinatorApi, like the monitor calls above. */
    suspend fun pollNotifications(instanceId: InstanceId, since: Long, waitMs: Long): NotificationsPage =
        api.pollNotifications(instanceId, since, waitMs)

    suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail =
        api.getRunDetail(instanceId, runId)

    suspend fun cancelRun(instanceId: InstanceId, runId: String) =
        api.cancelRun(instanceId, runId)

    suspend fun injectRunMessage(instanceId: InstanceId, runId: String, text: String): String =
        api.injectRunMessage(instanceId, runId, text)

    /** POST /runs/{id}/approve — workflow gate (KMA-288). */
    suspend fun approveRun(instanceId: InstanceId, runId: String, workflowRunId: String) =
        api.approveRun(instanceId, runId, workflowRunId)

    /** POST /runs/{id}/reject — workflow gate ack (KMA-288). */
    suspend fun rejectRun(instanceId: InstanceId, runId: String, workflowRunId: String) =
        api.rejectRun(instanceId, runId, workflowRunId)

    /** POST /runs/{id}/edits/accept (KMA-288). */
    suspend fun acceptRunEdits(
        instanceId: InstanceId,
        runId: String,
        request: ApplyEditsRequest,
    ): ApplyEditsResult = api.acceptRunEdits(instanceId, runId, request)

    /** POST /runs/{id}/edits/reject (KMA-288). */
    suspend fun rejectRunEdits(
        instanceId: InstanceId,
        runId: String,
        request: ApplyEditsRequest,
    ) = api.rejectRunEdits(instanceId, runId, request)

    // Remote chat (protocol §2.4, PRD.md §6.4). The flow opens the chat
    // socket on collect and closes it on cancel; send throws IOException
    // when no stream is open/writable so the UI can keep the draft and show
    // the disconnected state instead of silently dropping the message.
    fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage> =
        api.observeRemoteChat(instanceId)

    suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String) =
        api.sendRemoteChatMessage(instanceId, text)

    fun observeConnectionState(instanceId: InstanceId): Flow<CoordinatorConnectionState> =
        api.observeConnectionState(instanceId)
}
