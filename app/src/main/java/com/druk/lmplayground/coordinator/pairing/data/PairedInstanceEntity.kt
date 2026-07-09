package com.druk.lmplayground.coordinator.pairing.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable record of a paired `lclreason` instance (protocol §2.2). Kept in
 * `chidori_coordinator.db`, a database file separate from the app's main
 * `lmplayground.db` (see [CoordinatorDatabase]) so `net/coordinator` stays
 * free of any compile- or schema-time coupling to `data/AppDatabase`, per
 * `CHIDORI_PROTOCOL.md` §3.4.
 */
@Entity(tableName = "paired_instances")
data class PairedInstanceEntity(
    @PrimaryKey val instanceId: String,
    val displayName: String,
    val lastKnownHost: String,
    val lastKnownPort: Int,
    /** One of PairingState.name — stored as a string for easy manual DB inspection while debugging. */
    val pairingState: String,
    val nodeModeEnabled: Boolean,
    /** Bearer token issued at pairing confirmation (WIRE_CONTRACT.md). Cleared on unpair. */
    val authToken: String?,
    val pairedAtEpochMillis: Long,
)
