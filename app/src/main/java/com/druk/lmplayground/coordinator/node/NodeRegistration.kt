package com.druk.lmplayground.coordinator.node

import com.druk.lmplayground.coordinator.model.InstanceId

/**
 * Node mode (protocol §2.5): the phone offering its own on-device model to
 * a `lclreason` coordinator as an inference worker, the same way the
 * desktop app already treats a local Ollama instance or a remote
 * OpenAI-compatible API as an attachable inference source.
 *
 * NOT IMPLEMENTED. This interface exists only so `net/coordinator`'s
 * pairing/transport layer (see `pairing/`, `transport/`) is built without
 * assuming the phone is always the dependent side of the relationship —
 * see PRD.md §8.1 and ROADMAP.md Phase 3.5. Do not add real behavior here
 * without a protocol amendment and a matching roadmap update; node mode's
 * actual desktop-side routing is lclreason's implementation, not this
 * repo's call to make unilaterally.
 */
interface NodeRegistrationCapability {
    /** Whether this build/instance offers node mode at all (kill switch, independent of per-pairing opt-in). */
    val isSupported: Boolean

    /**
     * Registers this phone as an inference worker with the given paired
     * instance. Off by default per protocol §2.5 — battery/thermal cost and
     * it exposes the model to desktop-initiated requests.
     */
    suspend fun registerAsNode(instanceId: InstanceId): Result<Unit>

    suspend fun unregisterAsNode(instanceId: InstanceId)
}

/** Always-unsupported stub — prefer [DefaultNodeRegistrationCapability] in production. */
object UnimplementedNodeRegistrationCapability : NodeRegistrationCapability {
    override val isSupported: Boolean = false

    override suspend fun registerAsNode(instanceId: InstanceId): Result<Unit> =
        Result.failure(UnsupportedOperationException("Node mode is not implemented yet — see ROADMAP.md Phase 3.5 / Phase 5."))

    override suspend fun unregisterAsNode(instanceId: InstanceId) {
        // No-op: nothing was ever registered.
    }
}
