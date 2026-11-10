package com.druk.lmplayground.checkin

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room access for [OpenItemEntity]. Business rules live in [OpenItemRepository]. */
@Dao
interface OpenItemDao {
    @Insert
    suspend fun insert(item: OpenItemEntity)

    @Update
    suspend fun update(item: OpenItemEntity)

    @Query("SELECT * FROM open_items WHERE id = :id")
    suspend fun getById(id: String): OpenItemEntity?

    @Query("SELECT * FROM open_items WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): OpenItemEntity?

    @Query("SELECT * FROM open_items WHERE source = :source AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(source: String, sourceId: String): OpenItemEntity?

    @Query(
        """
        SELECT * FROM open_items
        WHERE status IN ('OPEN', 'RAISED')
        ORDER BY createdAt ASC
        """,
    )
    suspend fun listActionable(): List<OpenItemEntity>

    @Query(
        """
        SELECT * FROM open_items
        WHERE sessionId = :sessionId AND status IN ('OPEN', 'RAISED')
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun latestActionableForSession(sessionId: String): OpenItemEntity?

    @Query(
        """
        SELECT * FROM open_items
        WHERE sessionId = :sessionId AND status = 'RAISED'
        """,
    )
    suspend fun raisedForSession(sessionId: String): List<OpenItemEntity>

    @Query("SELECT * FROM open_items ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<OpenItemEntity>>
}
