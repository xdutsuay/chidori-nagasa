package com.druk.lmplayground.coordinator

import android.content.Context
import com.druk.lmplayground.coordinator.discovery.InstanceDiscovery
import com.druk.lmplayground.coordinator.discovery.NsdInstanceDiscovery
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.ManualEndpoint
import com.druk.lmplayground.coordinator.model.PairedInstance
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

    // Coordinator status/run/chat access (protocol §2.4) is exposed via
    // CoordinatorApi directly today rather than re-wrapped here, since it's
    // still a Phase 2/3 concern with no UI consumer yet — see ROADMAP.md.
    // Once a ViewModel needs it, add typed pass-throughs here rather than
    // having that ViewModel reach into `transport` directly (protocol §3.4).
}
