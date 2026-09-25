package com.druk.lmplayground.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys in [EncryptedSharedPreferences] backed by the Android Keystore.
 * Construction fails closed: if MasterKey / prefs cannot be created, every
 * write throws [CloudKeyStoreException] and reads return null.
 */
class EncryptedCloudKeyStore private constructor(
    private val prefs: SharedPreferences?,
    private val initError: Throwable?,
) : CloudKeyStore {

    override fun getKey(providerId: String): String? {
        val store = prefs ?: return null
        return store.getString(prefKey(providerId), null)?.takeIf { it.isNotBlank() }
    }

    override fun setKey(providerId: String, key: String) {
        val store = prefs
            ?: throw CloudKeyStoreException(
                "Secure key storage is unavailable on this device",
                initError,
            )
        val trimmed = key.trim()
        store.edit().apply {
            if (trimmed.isEmpty()) {
                remove(prefKey(providerId))
            } else {
                putString(prefKey(providerId), trimmed)
            }
        }.apply()
    }

    override fun deleteKey(providerId: String) {
        val store = prefs ?: return
        store.edit().remove(prefKey(providerId)).apply()
    }

    companion object {
        private const val PREFS_NAME = "cloud_api_keys"
        private const val KEY_PREFIX = "provider."

        fun create(context: Context): EncryptedCloudKeyStore {
            return try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                EncryptedCloudKeyStore(prefs, null)
            } catch (t: Throwable) {
                EncryptedCloudKeyStore(null, t)
            }
        }

        /** Test / JVM helper that wraps an in-memory SharedPreferences-like map. */
        fun forTesting(prefs: SharedPreferences): EncryptedCloudKeyStore =
            EncryptedCloudKeyStore(prefs, null)

        fun prefKey(providerId: String): String = KEY_PREFIX + providerId
    }
}
