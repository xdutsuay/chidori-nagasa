package com.druk.lmplayground.checkin

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One unfinished loop Nagasa may mention in a check-in (KMA-333).
 *
 * [initialProbeState] is frozen at capture. [stateAtLastRaise] is frozen
 * when the item is included in a nudge. [lastProbeState] is the latest
 * probe and is updated even when the item is not raised, so the two
 * baselines stay distinct.
 */
@Entity(
    tableName = "open_items",
    indices = [
        Index("status"),
        Index(value = ["fingerprint"], unique = true),
        Index("source"),
        Index(value = ["source", "sourceId"]),
    ],
)
data class OpenItemEntity(
    @PrimaryKey val id: String,
    val source: String,
    val sourceId: String,
    val sessionId: String? = null,
    val sourceMessageId: Long? = null,
    val kind: String,
    val summary: String,
    val dueAt: Long? = null,
    val probeJson: String,
    val status: String,
    val lastRaisedAt: Long? = null,
    val lastProbeState: String,
    val initialProbeState: String,
    val stateAtLastRaise: String? = null,
    val fingerprint: String,
    val fresh: Boolean = false,
    val doneReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
