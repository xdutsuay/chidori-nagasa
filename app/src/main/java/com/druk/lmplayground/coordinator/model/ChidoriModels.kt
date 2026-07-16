package com.druk.lmplayground.coordinator.model

/**
 * Shared wire-contract data models for talking to a `lclreason` desktop
 * instance's Inference Coordinator. See CHIDORI_PROTOCOL.md §2 before
 * changing anything in this file — these shapes are the contract, not
 * internal implementation detail.
 */

/** Semver-ish protocol version, e.g. "1.2.0". Negotiated per protocol §2.3. */
@JvmInline
value class ProtocolVersion(val value: String) {
    companion object {
        /** Highest wire-contract version this client currently supports. */
        val CURRENT = ProtocolVersion("1.2.0")

        /** Default companion listen port (protocol 1.2.0) — IDE stays on 8080. */
        const val DEFAULT_COMPANION_PORT = 8027
    }
}

/** Stable identifier for one running `lclreason` desktop instance (protocol §2.2). */
@JvmInline
value class InstanceId(val value: String)

/**
 * An instance discovered on the LAN but not yet paired with. Produced by
 * `discovery.InstanceDiscovery`, consumed by `pairing.PairingManager`.
 */
data class DiscoveredInstance(
    val instanceId: InstanceId,
    val displayName: String,
    val host: String,
    val port: Int,
    val protocolVersion: ProtocolVersion,
    val pairingRequired: Boolean,
)

/** A manually-entered fallback target, before discovery/pairing resolves an instanceId. */
data class ManualEndpoint(
    val host: String,
    val port: Int,
)

enum class PairingState {
    NOT_PAIRED,
    PAIRING_IN_PROGRESS,
    PAIRED,
    REVOKED,
    /** instanceId changed since last pairing — protocol §2.2 requires re-pairing, not silent trust. */
    REQUIRES_REPAIR,
}

data class PairedInstance(
    val instanceId: InstanceId,
    val displayName: String,
    val lastKnownHost: String,
    val lastKnownPort: Int,
    val pairingState: PairingState,
    /** Node mode is opt-in and off by default per protocol §2.5. */
    val nodeModeEnabled: Boolean = false,
)

enum class CoordinatorConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /** protocol_version mismatch the client can't speak — protocol §2.3. */
    UNSUPPORTED_VERSION,
}

enum class CoordinatorStatus {
    IDLE,
    RUNNING,
    ERROR,
}

/** chidori's four AI reasoning modes, mirrored from the lclreason desktop app. */
enum class AgentMode {
    ASK,
    AGENT,
    PLAN,
    DEBUG,
}

enum class AgentRunState {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class AgentRunSummary(
    val runId: String,
    val mode: AgentMode,
    val startedAtEpochMillis: Long,
    val state: AgentRunState,
)

data class AgentRunDetail(
    val summary: AgentRunSummary,
    val currentStep: String?,
    /** Tail of the run's log output — read-only, protocol §2.4 (v1 has no control actions). */
    val logTail: List<String>,
)

/** One message in a chat routed through the desktop's attached local/remote LLM (protocol §2.4). */
data class RemoteChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val sentAtEpochMillis: Long,
)
