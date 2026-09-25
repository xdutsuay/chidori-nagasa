package com.druk.lmplayground.cloud

/**
 * Stores API secrets outside Room. Implementations must never log key material
 * or write it into plaintext SharedPreferences / the chat database.
 */
interface CloudKeyStore {
    /** Returns the stored key for [providerId], or null if none / Keystore unavailable. */
    fun getKey(providerId: String): String?

    /**
     * Persists [key] for [providerId]. Blank keys delete the entry.
     * @throws CloudKeyStoreException when the secure store cannot be opened.
     */
    fun setKey(providerId: String, key: String)

    fun deleteKey(providerId: String)

    fun hasKey(providerId: String): Boolean = !getKey(providerId).isNullOrBlank()
}

class CloudKeyStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
