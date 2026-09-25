package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completed generation (local, cloud, or desktop remote chat).
 * Cost is only stored when a provider returns it — never invented.
 */
@Entity(
    tableName = "usage_events",
    indices = [Index("createdAt"), Index("route"), Index("model")],
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** LOCAL, CLOUD, or DESKTOP. */
    val route: String,
    val provider: String = "",
    val model: String = "",
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** Micro-USD (costUsd * 1_000_000) when the API returned a cost. */
    val costMicros: Long? = null,
    val estimated: Boolean = false,
    val sessionId: String? = null,
)

object UsageRoute {
    const val LOCAL = "LOCAL"
    const val CLOUD = "CLOUD"
    const val DESKTOP = "DESKTOP"
}
