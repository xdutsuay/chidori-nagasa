package com.druk.lmplayground.cloud

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Saved cloud provider row. The API key lives in [CloudKeyStore], never here.
 */
@Entity(tableName = "cloud_providers")
data class CloudProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String = "",
    val allowHttp: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
