package com.druk.lmplayground.computer

import java.util.UUID

/**
 * Stable COS session id so a later desktop attach can resume the same phone session.
 */
object CosSession {
    const val KEY_SESSION_ID = "cos.session_id"

    fun newSessionId(): String = UUID.randomUUID().toString()
}

/** String-capable backend for session id. */
interface CosSessionBackend {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemorySessionBackend : CosSessionBackend {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class CosSessionStore(private val backend: CosSessionBackend) {
    fun currentSessionId(): String {
        val existing = backend.getString(CosSession.KEY_SESSION_ID)
        if (!existing.isNullOrBlank()) return existing
        val id = CosSession.newSessionId()
        backend.putString(CosSession.KEY_SESSION_ID, id)
        return id
    }

    fun rotateSessionId(): String {
        val id = CosSession.newSessionId()
        backend.putString(CosSession.KEY_SESSION_ID, id)
        return id
    }
}
