package com.druk.lmplayground.coordinator

import android.content.Context
import com.druk.lmplayground.coordinator.discovery.InstanceDiscovery
import com.druk.lmplayground.coordinator.discovery.NsdInstanceDiscovery
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ManualEndpoint
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.RemoteChatMessage
import com.druk.lmplayground.coordinator.node.NodeRegistrationCapability
import com.druk.lmplayground.coordinator.node.UnimplementedNodeRegistrationCapability
import com.druk.lmplayground.coordinator.pairing.PairedInstanceStore
import com.druk.lmplayground.coordinator.pairing.PairingManager
import com.druk.lmplayground.coordinator.pairing.PairingManagerImpl
import com.druk.lmplayground.coordinator.pairing.RoomPairedInstanceStore
import com.druk.lmplayground.coordinator.pairing.data.CoordinatorDatabase
import com.druk.lmplayground.coordinator.transport.CoordinatorApi
import com.druk.lmplayground.coordinator.transport.OkHttpCoordinatorApi
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

    val nodeRegistration: NodeRegistrationCapability = UnimplementedNodeRegistrationCapability

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
    suspend fun getStatus(instanceId: InstanceId): CoordinatorStatus = api.getStatus(instanceId)

    suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary> = api.listRuns(instanceId)

    suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail =
        api.getRunDetail(instanceId, runId)

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
