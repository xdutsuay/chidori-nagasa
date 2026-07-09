package com.druk.lmplayground.coordinator.discovery

import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import kotlinx.coroutines.flow.Flow

/**
 * Finds `lclreason` desktop instances on the local network. Protocol §2.1:
 * mDNS/NSD is the primary path, manual host:port entry is a required
 * fallback (not optional) because mDNS reliability varies across Android
 * OEM skins.
 */
interface InstanceDiscovery {
    /**
     * Emits the current set of discovered instances as they appear/disappear.
     * Implementations must de-duplicate by instanceId and drop an instance
     * from the emitted set once its advertisement stops (see TEST_PLAN.md §2.2).
     */
    fun observeDiscoveredInstances(): Flow<List<DiscoveredInstance>>

    fun startDiscovery()
    fun stopDiscovery()
}

/**
 * NSD-backed implementation targeting the `_chidori._tcp.local.` service
 * type (protocol §2.1). Stub — not yet wired to android.net.nsd.NsdManager.
 *
 * TODO(coordinator, Phase 2): implement using NsdManager.discoverServices +
 * resolveService, parsing protocol_version / instance_id / pairing_required
 * out of the TXT record. Needs a real Android runtime to verify NSD
 * behavior across OEMs (protocol §3.2-adjacent risk, even though this isn't
 * the native layer — NSD implementations are notoriously inconsistent).
 */
class NsdInstanceDiscovery : InstanceDiscovery {
    override fun observeDiscoveredInstances(): Flow<List<DiscoveredInstance>> {
        TODO("Not implemented — see ROADMAP.md Phase 2. Do not fill this in before the joint wire-contract spec (Phase 1) is agreed with lclreason.")
    }

    override fun startDiscovery() {
        TODO("Not implemented — see ROADMAP.md Phase 2.")
    }

    override fun stopDiscovery() {
        TODO("Not implemented — see ROADMAP.md Phase 2.")
    }
}
