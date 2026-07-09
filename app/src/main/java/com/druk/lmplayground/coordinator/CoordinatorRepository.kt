package com.druk.lmplayground.coordinator

import com.druk.lmplayground.coordinator.discovery.InstanceDiscovery
import com.druk.lmplayground.coordinator.discovery.NsdInstanceDiscovery
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.ManualEndpoint
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.node.NodeRegistrationCapability
import com.druk.lmplayground.coordinator.node.UnimplementedNodeRegistrationCapability
import com.druk.lmplayground.coordinator.pairing.InMemoryPairedInstanceStore
import com.druk.lmplayground.coordinator.pairing.PairedInstanceStore
import com.druk.lmplayground.coordinator.transport.CoordinatorApi
import com.druk.lmplayground.coordinator.transport.OkHttpCoordinatorApi
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point for everything in `net/coordinator` (protocol §3.4:
 * "UI code never constructs coordinator requests directly"). UI/ViewModel
 * code should only ever talk to this class, never to `discovery`,
 * `pairing`, `transport`, or `node` types directly.
 *
 * This is a first-draft skeleton wired with stub implementations — see
 * each sub-package's TODOs and `coordinator/README.md`. It's here so the
 * module boundary and dependency shape exist from day one, per
 * CHIDORI_PROTOCOL.md §2.5's forward-compatibility requirement, even
 * though the client-mode features (discovery/pairing/monitor/chat) aren't
 * functional yet.
 */
class CoordinatorRepository(
    private val discovery: InstanceDiscovery = NsdInstanceDiscovery(),
    private val pairedInstanceStore: PairedInstanceStore = InMemoryPairedInstanceStore(),
    private val api: CoordinatorApi = OkHttpCoordinatorApi(),
    val nodeRegistration: NodeRegistrationCapability = UnimplementedNodeRegistrationCapability,
) {

    fun observeDiscoveredInstances(): Flow<List<DiscoveredInstance>> =
        discovery.observeDiscoveredInstances()

    fun startDiscovery() = discovery.startDiscovery()

    fun stopDiscovery() = discovery.stopDiscovery()

    suspend fun pairedInstances(): List<PairedInstance> = pairedInstanceStore.getAll()

    /**
     * Manual host:port fallback per protocol §2.1 — always available, not
     * gated behind a "troubleshooting" menu (PRD.md §6.2).
     */
    fun manualEndpoint(host: String, port: Int): ManualEndpoint = ManualEndpoint(host, port)

    // Client-mode status/run/chat access is intentionally not exposed here
    // yet — wire it up alongside the real CoordinatorApi implementation in
    // ROADMAP.md Phase 2/3, once the joint wire-contract spec (Phase 1) is
    // settled. Exposing typed pass-throughs to `api` before that would
    // just be guessing at a contract we don't have yet.
}
