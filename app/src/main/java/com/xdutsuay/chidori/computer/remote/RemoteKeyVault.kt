package com.xdutsuay.chidori.computer.remote

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Per-computer SSH identities in app-private storage, encrypted with an Android
 * Keystore master key. Private keys never leave this class except to the transport.
 */
class RemoteKeyVault(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR).apply { mkdirs() }
    private val masterKey by lazy {
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    fun load(id: String): SshIdentity? {
        val f = file(id)
        if (!f.exists()) return null
        return runCatching {
            encrypted(f).openFileInput().use { input ->
                val data = DataInputStream(input)
                val priv = ByteArray(data.readInt()).also { data.readFully(it) }
                val pub = ByteArray(data.readInt()).also { data.readFully(it) }
                SshIdentity(priv, pub)
            }
        }.getOrNull()
    }

    fun loadOrCreate(id: String): SshIdentity = load(id) ?: rotate(id)

    /** Replaces the identity for [id]; the user must re-copy the public key to the VM. */
    fun rotate(id: String): SshIdentity {
        delete(id)
        val identity = SshKeyGen.generate()
        val bytes = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { out ->
                out.writeInt(identity.privateKeyPem.size)
                out.write(identity.privateKeyPem)
                out.writeInt(identity.publicKeyBlob.size)
                out.write(identity.publicKeyBlob)
            }
        }.toByteArray()
        encrypted(file(id)).openFileOutput().use { it.write(bytes) }
        return identity
    }

    fun delete(id: String) {
        file(id).delete()
    }

    private fun file(id: String) = File(dir, id.filter { it.isLetterOrDigit() || it == '-' } + ".key")

    private fun encrypted(f: File) = EncryptedFile.Builder(
        appContext,
        f,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private companion object {
        const val DIR = "remote_keys"
    }
}
