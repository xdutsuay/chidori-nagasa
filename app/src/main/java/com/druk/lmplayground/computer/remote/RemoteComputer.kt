package com.druk.lmplayground.computer.remote

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** A user-provided SSH target (e.g. a cloud VM they started themselves). Connect-only. */
data class RemoteComputer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val hostKeyFingerprint: String? = null,
)

object RemoteComputerValidation {
    enum class Field { NAME, HOST, PORT, USER }

    private const val MAX_NAME = 40
    private const val MAX_HOST = 253
    private val USER = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,31}$")

    fun validate(name: String, host: String, port: Int, user: String): Set<Field> {
        val bad = mutableSetOf<Field>()
        val n = name.trim()
        val h = host.trim()
        val u = user.trim()
        if (n.isEmpty() || n.length > MAX_NAME) bad += Field.NAME
        if (h.isEmpty() || h.length > MAX_HOST || h.any { it.isWhitespace() } ||
            "://" in h || '/' in h || '@' in h
        ) {
            bad += Field.HOST
        }
        if (port !in 1..65535) bad += Field.PORT
        if (!USER.matches(u)) bad += Field.USER
        return bad
    }
}

interface RemoteComputerBackend {
    fun read(): String?
    fun write(json: String)
}

class RemoteComputerStore(
    private val backend: RemoteComputerBackend,
    private val newId: () -> String,
) {
    fun list(): List<RemoteComputer> {
        val raw = backend.read()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RemoteComputer(
                    id = o.getString(K_ID),
                    name = o.getString(K_NAME),
                    host = o.getString(K_HOST),
                    port = o.optInt(K_PORT, DEFAULT_PORT),
                    user = o.getString(K_USER),
                    hostKeyFingerprint = if (o.isNull(K_FP)) null else o.optString(K_FP).ifBlank { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): RemoteComputer? = list().firstOrNull { it.id == id }

    fun add(name: String, host: String, port: Int, user: String): RemoteComputer {
        val bad = RemoteComputerValidation.validate(name, host, port, user)
        require(bad.isEmpty()) { "invalid fields: $bad" }
        val c = RemoteComputer(newId(), name.trim(), host.trim(), port, user.trim())
        save(list() + c)
        return c
    }

    fun pinHostKey(id: String, fingerprint: String): RemoteComputer? =
        update(id) { it.copy(hostKeyFingerprint = fingerprint) }

    fun clearHostKey(id: String): RemoteComputer? = update(id) { it.copy(hostKeyFingerprint = null) }

    fun remove(id: String): Boolean {
        val all = list()
        val kept = all.filterNot { it.id == id }
        if (kept.size == all.size) return false
        save(kept)
        return true
    }

    private fun update(id: String, f: (RemoteComputer) -> RemoteComputer): RemoteComputer? {
        var updated: RemoteComputer? = null
        val all = list().map { if (it.id == id) f(it).also { u -> updated = u } else it }
        if (updated != null) save(all)
        return updated
    }

    private fun save(all: List<RemoteComputer>) {
        val arr = JSONArray()
        all.forEach { c ->
            arr.put(
                JSONObject()
                    .put(K_ID, c.id)
                    .put(K_NAME, c.name)
                    .put(K_HOST, c.host)
                    .put(K_PORT, c.port)
                    .put(K_USER, c.user)
                    .put(K_FP, c.hostKeyFingerprint ?: JSONObject.NULL),
            )
        }
        backend.write(arr.toString())
    }

    private companion object {
        const val DEFAULT_PORT = 22
        const val K_ID = "id"
        const val K_NAME = "name"
        const val K_HOST = "host"
        const val K_PORT = "port"
        const val K_USER = "user"
        const val K_FP = "host_key_sha256"
    }
}

/** Trust-on-first-use host key pinning; a changed key is refused until the user resolves it. */
object HostKeyPolicy {
    enum class Decision { TRUST_ON_FIRST_USE, MATCH, MISMATCH }

    fun decide(pinned: String?, presented: String): Decision = when {
        pinned.isNullOrBlank() -> Decision.TRUST_ON_FIRST_USE
        pinned == presented -> Decision.MATCH
        else -> Decision.MISMATCH
    }
}

/** OpenSSH-compatible fingerprint and `authorized_keys` line formatting. */
object SshKeyFormat {
    fun fingerprintSha256(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }

    fun authorizedKeysLine(type: String, publicKeyBlob: ByteArray, comment: String): String {
        val b64 = Base64.getEncoder().encodeToString(publicKeyBlob)
        return if (comment.isBlank()) "$type $b64" else "$type $b64 $comment"
    }
}
