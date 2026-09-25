package com.druk.lmplayground.cloud

/**
 * CRUD for [CloudProviderEntity]. Deletes the API key before dropping the row.
 */
class CloudProviderRepository(
    private val dao: CloudProviderDao,
    private val keyStore: CloudKeyStore,
) {
    fun observeAll() = dao.observeAll()

    suspend fun getAll() = dao.getAll()

    suspend fun getById(id: String) = dao.getById(id)

    suspend fun saveProvider(provider: CloudProviderEntity, apiKey: String?) {
        if (apiKey != null) {
            keyStore.setKey(provider.id, apiKey)
        }
        dao.upsert(provider.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteProvider(id: String) {
        keyStore.deleteKey(id)
        dao.delete(id)
    }

    fun getKey(providerId: String): String? = keyStore.getKey(providerId)

    fun hasKey(providerId: String): Boolean = keyStore.hasKey(providerId)
}
