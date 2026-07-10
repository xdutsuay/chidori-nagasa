package com.druk.lmplayground.coordinator.pairing

import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState
import com.druk.lmplayground.coordinator.pairing.data.PairedInstanceDao
import com.druk.lmplayground.coordinator.pairing.data.PairedInstanceEntity
import com.druk.lmplayground.coordinator.transport.CoordinatorApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * Where paired instances actually live — a thin mapper over
 * [PairedInstanceDao] (Room, `chidori_coordinator.db`; see
 * `pairing/data/CoordinatorDatabase.kt`). Kept as a separate interface from
 * [PairingManager] so the persistence choice doesn't leak into pairing-flow
 * logic, and so tests can swap in a fake without touching Room.
 */
interface PairedInstanceStore {
    fun observeAll(): Flow<List<PairedInstance>>
    suspend fun getAll(): List<PairedInstance>
    suspend fun get(instanceId: InstanceId): PairedInstance?
    suspend fun upsert(instance: PairedInstance, authToken: String?)
    suspend fun getAuthToken(instanceId: InstanceId): String?
    suspend fun remove(instanceId: InstanceId)
}

class RoomPairedInstanceStore(private val dao: PairedInstanceDao) : PairedInstanceStore {

    override fun observeAll(): Flow<List<PairedInstance>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun getAll(): List<PairedInstance> = dao.getAll().map { it.toModel() }

    override suspend fun get(instanceId: InstanceId): PairedInstance? =
        dao.get(instanceId.value)?.toModel()

    override suspend fun upsert(instance: PairedInstance, authToken: String?) {
        val existing = dao.get(instance.instanceId.value)
        dao.upsert(
            PairedInstanceEntity(
                instanceId = instance.instanceId.value,
                displayName = instance.displayName,
                lastKnownHost = instance.lastKnownHost,
                lastKnownPort = instance.lastKnownPort,
                pairingState = instance.pairingState.name,
                nodeModeEnabled = instance.nodeModeEnabled,
                // Preserve the existing token on updates that don't carry a new one
                // (e.g. re-resolving host/port on the LAN shouldn't clear auth).
                authToken = authToken ?: existing?.authToken,
                pairedAtEpochMillis = existing?.pairedAtEpochMillis ?: System.currentTimeMillis(),
            )
        )
    }

    override suspend fun getAuthToken(instanceId: InstanceId): String? =
        dao.get(instanceId.value)?.authToken

    override suspend fun remove(instanceId: InstanceId) {
        dao.delete(instanceId.value)
    }

    private fun PairedInstanceEntity.toModel() = PairedInstance(
        instanceId = InstanceId(instanceId),
        displayName = displayName,
        lastKnownHost = lastKnownHost,
        lastKnownPort = lastKnownPort,
        pairingState = runCatching { PairingState.valueOf(pairingState) }.getOrDefault(PairingState.REQUIRES_REPAIR),
        nodeModeEnabled = nodeModeEnabled,
    )
}

/**
 * Real pairing-flow implementation against [CoordinatorApi], written to
 * WIRE_CONTRACT.md's draft `/pairing/begin` + `/pairing/confirm` shape.
 * That draft is not yet reconciled with `lclreason`'s actual API (see
 * WIRE_CONTRACT.md's closing section) — treat this as implementing a
 * proposal, not a verified-working contract, until that reconciliation
 * happens and this file is revisited.
 */
class PairingManagerImpl(
    private val store: PairedInstanceStore,
    private val api: CoordinatorApi,
) : PairingManager {

    override fun observePairedInstances(): Flow<List<PairedInstance>> = store.observeAll()

    override suspend fun beginPairing(instance: DiscoveredInstance): PairingState {
        store.upsert(
            PairedInstance(
                instanceId = instance.instanceId,
                displayName = instance.displayName,
                lastKnownHost = instance.host,
                lastKnownPort = instance.port,
                pairingState = PairingState.PAIRING_IN_PROGRESS,
            ),
            authToken = null,
        )
        val started = api.beginPairing(instance.instanceId, instance.host, instance.port)
        return if (started) PairingState.PAIRING_IN_PROGRESS else PairingState.NOT_PAIRED
    }

    override suspend fun confirmPairingCode(instanceId: InstanceId, code: String): PairingState {
        val existing = store.get(instanceId) ?: return PairingState.NOT_PAIRED
        val result = api.confirmPairing(instanceId, code)
        return if (result != null) {
            // Manual host:port pairing starts under a placeholder instanceId
            // (the phone can't know the real one before first contact —
            // protocol §2.2 keys trust by instance_id, not IP). The confirm
            // response carries the server-asserted id; re-key the record to
            // it so future discovery/monitor lookups match.
            val confirmedId = result.instanceId ?: instanceId
            if (confirmedId != instanceId) store.remove(instanceId)
            store.upsert(
                existing.copy(instanceId = confirmedId, pairingState = PairingState.PAIRED),
                authToken = result.authToken,
            )
            PairingState.PAIRED
        } else {
            store.upsert(existing.copy(pairingState = PairingState.NOT_PAIRED), authToken = null)
            PairingState.NOT_PAIRED
        }
    }

    override suspend fun unpair(instanceId: InstanceId) {
        // Best-effort remote revocation first so the desktop side also
        // drops the token; local removal happens regardless of whether the
        // network call succeeds, since protocol §2.2 requires unpair to
        // take effect on this phone's next request no matter what.
        runCatching { api.revokePairing(instanceId) }
        store.remove(instanceId)
    }
}
