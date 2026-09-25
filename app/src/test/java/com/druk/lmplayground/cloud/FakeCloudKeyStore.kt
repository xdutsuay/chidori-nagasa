package com.druk.lmplayground.cloud

/**
 * In-memory [CloudKeyStore] for unit tests. Never used in production.
 */
class FakeCloudKeyStore : CloudKeyStore {
    private val keys = mutableMapOf<String, String>()
    var failWrites: Boolean = false

    override fun getKey(providerId: String): String? = keys[providerId]

    override fun setKey(providerId: String, key: String) {
        if (failWrites) {
            throw CloudKeyStoreException("forced failure")
        }
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            keys.remove(providerId)
        } else {
            keys[providerId] = trimmed
        }
    }

    override fun deleteKey(providerId: String) {
        keys.remove(providerId)
    }
}
