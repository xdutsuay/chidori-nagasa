package com.druk.lmplayground.coordinator.pairing

import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState
import kotlinx.coroutines.flow.Flow

/**
 * The trust handshake between this phone and a `lclreason` desktop
 * instance (protocol §2.2). No silent/automatic trust of a
 * newly-discovered instance — every first connection requires an explicit
 * pairing code/QR confirmation.
 */
interface PairingManager {
    fun observePairedInstances(): Flow<List<PairedInstance>>

    /** Begins pairing against a discovered (or manually-entered) instance. */
    suspend fun beginPairing(instance: DiscoveredInstance): PairingState

    /** Confirms a pairing code shown by the desktop app. */
    suspend fun confirmPairingCode(instanceId: InstanceId, code: String): PairingState

    /**
     * Revokes a pairing. Per protocol §2.2, revocation must take effect on
     * the phone's *next request*, not next launch — callers must not cache
     * "is paired" state past what this store reports.
     */
    suspend fun unpair(instanceId: InstanceId)
}

/**
 * Where paired instances actually live. Kept as a separate interface from
 * PairingManager so the persistence choice (Room, DataStore, etc.) isn't
 * baked into the pairing-flow logic.
 *
 * Stub: in-memory only for this first draft, so this compiles and is
 * testable without introducing a Room schema that hasn't been verified
 * against a real Gradle/KSP build. Swap for a durable store (Room, matching
 * the app's existing data/ conventions — see AppDatabase.kt) before this
 * leaves the stub stage; an in-memory store loses pairings on process death,
 * which is not acceptable for v1.
 */
class InMemoryPairedInstanceStore : PairedInstanceStore {
    private val instances = linkedMapOf<InstanceId, PairedInstance>()

    override suspend fun getAll(): List<PairedInstance> = instances.values.toList()

    override suspend fun get(instanceId: InstanceId): PairedInstance? = instances[instanceId]

    override suspend fun upsert(instance: PairedInstance) {
        instances[instance.instanceId] = instance
    }

    override suspend fun remove(instanceId: InstanceId) {
        instances.remove(instanceId)
    }
}

interface PairedInstanceStore {
    suspend fun getAll(): List<PairedInstance>
    suspend fun get(instanceId: InstanceId): PairedInstance?
    suspend fun upsert(instance: PairedInstance)
    suspend fun remove(instanceId: InstanceId)
}
