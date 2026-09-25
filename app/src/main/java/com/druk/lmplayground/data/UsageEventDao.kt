package com.druk.lmplayground.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageEventDao {
    @Insert
    suspend fun insert(event: UsageEventEntity): Long

    @Query(
        """
        SELECT * FROM usage_events
        WHERE createdAt >= :sinceMs
        ORDER BY createdAt DESC
        """,
    )
    suspend fun since(sinceMs: Long): List<UsageEventEntity>

    @Query("DELETE FROM usage_events")
    suspend fun deleteAll()
}
