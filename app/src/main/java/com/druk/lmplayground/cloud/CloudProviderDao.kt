package com.druk.lmplayground.cloud

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface CloudProviderDao {
    @Query("SELECT * FROM cloud_providers ORDER BY updatedAt DESC")
    fun observeAll(): LiveData<List<CloudProviderEntity>>

    @Query("SELECT * FROM cloud_providers ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CloudProviderEntity>

    @Query("SELECT * FROM cloud_providers WHERE id = :id")
    suspend fun getById(id: String): CloudProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: CloudProviderEntity)

    @Update
    suspend fun update(provider: CloudProviderEntity)

    @Query("DELETE FROM cloud_providers WHERE id = :id")
    suspend fun delete(id: String)
}
