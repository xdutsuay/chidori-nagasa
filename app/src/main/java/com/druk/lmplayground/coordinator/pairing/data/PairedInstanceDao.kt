package com.druk.lmplayground.coordinator.pairing.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedInstanceDao {

    @Query("SELECT * FROM paired_instances ORDER BY pairedAtEpochMillis DESC")
    fun observeAll(): Flow<List<PairedInstanceEntity>>

    @Query("SELECT * FROM paired_instances ORDER BY pairedAtEpochMillis DESC")
    suspend fun getAll(): List<PairedInstanceEntity>

    @Query("SELECT * FROM paired_instances WHERE instanceId = :instanceId")
    suspend fun get(instanceId: String): PairedInstanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PairedInstanceEntity)

    @Query("DELETE FROM paired_instances WHERE instanceId = :instanceId")
    suspend fun delete(instanceId: String)
}
