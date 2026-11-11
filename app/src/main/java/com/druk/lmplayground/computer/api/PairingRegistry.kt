package com.druk.lmplayground.computer.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

data class PairingCode(val code: String, val expiresAtMs: Long)

data class ApiClient(
    val id: String,
    val name: String,
    val address: String,
    val pairedAtMs: Long,
    val lastSeenMs: Long,
)

sealed interface Redeem {
    data class Ok(val client: ApiClient, val token: String) : Redeem
    data object Rejected : Redeem
}

/**
 * Pairing codes and paired clients for the Wi-Fi Computer API (slice 3). Thread-safe, pure.
 *
 * - [issueCode] makes a code from [codeSource] valid for [codeTtlMs], replacing any current
 *   code and resetting the failed-attempt count; [code] shows it. [randomCode] is 6 digits.
 * - [redeem]: no current code, an expired code (also clears it), or a wrong code → [Redeem.Rejected].
 *   Each wrong code counts; after [maxAttempts] wrong codes the current code is cleared, so even
 *   the right code is then rejected until a new [issueCode]. A right code succeeds once (the code
 *   is cleared): it adds an [ApiClient] (id from [idSource], name trimmed and cut to
 *   [MAX_NAME_CHARS], blank → [DEFAULT_NAME], [address] as given, paired/lastSeen = now) and
 *   returns a new token from [tokenSource].
 * - Tokens are kept only as SHA-256 hashes. [authenticate] compares in constant time; a known
 *   token returns its client and updates lastSeenMs; unknown, empty, or revoked → null.
 * - [revoke] removes one client (its token stops working); [revokeAll] removes all and clears
 *   the code. [clients] lists clients in pairing order.
 */
class PairingRegistry(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val codeSource: () -> String = ::randomCode,
    private val tokenSource: () -> String = ComputerApiShare::randomToken,
    private val idSource: () -> String = { java.util.UUID.randomUUID().toString() },
    private val codeTtlMs: Long = DEFAULT_CODE_TTL_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val _code = MutableStateFlow<PairingCode?>(null)
    val code: StateFlow<PairingCode?> = _code.asStateFlow()

    private val _clients = MutableStateFlow<List<ApiClient>>(emptyList())
    val clients: StateFlow<List<ApiClient>> = _clients.asStateFlow()

    private val lock = Any()
    private var failedAttempts = 0
    private val tokenHashes = LinkedHashMap<String, ByteArray>()

    fun issueCode(): PairingCode = synchronized(lock) {
        failedAttempts = 0
        PairingCode(codeSource(), nowMs() + codeTtlMs).also { _code.value = it }
    }

    fun redeem(code: String, name: String, address: String): Redeem = synchronized(lock) {
        val current = _code.value ?: return Redeem.Rejected
        val now = nowMs()
        if (now > current.expiresAtMs) {
            _code.value = null
            return Redeem.Rejected
        }
        if (!MessageDigest.isEqual(code.toByteArray(), current.code.toByteArray())) {
            failedAttempts++
            if (failedAttempts >= maxAttempts) _code.value = null
            return Redeem.Rejected
        }
        _code.value = null
        failedAttempts = 0
        val token = tokenSource()
        val client = ApiClient(
            id = idSource(),
            name = name.trim().take(MAX_NAME_CHARS).ifBlank { DEFAULT_NAME },
            address = address,
            pairedAtMs = now,
            lastSeenMs = now,
        )
        tokenHashes[client.id] = sha256(token)
        _clients.value = _clients.value + client
        Redeem.Ok(client, token)
    }

    fun authenticate(token: String): ApiClient? {
        if (token.isEmpty()) return null
        val hash = sha256(token)
        synchronized(lock) {
            var matchId: String? = null
            for ((id, h) in tokenHashes) {
                if (MessageDigest.isEqual(h, hash)) matchId = id
            }
            val id = matchId ?: return null
            val updated = _clients.value.map { if (it.id == id) it.copy(lastSeenMs = nowMs()) else it }
            _clients.value = updated
            return updated.firstOrNull { it.id == id }
        }
    }

    fun revoke(id: String) = synchronized(lock) {
        tokenHashes.remove(id)
        _clients.value = _clients.value.filterNot { it.id == id }
    }

    fun revokeAll() = synchronized(lock) {
        tokenHashes.clear()
        _clients.value = emptyList()
        _code.value = null
        failedAttempts = 0
    }

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    companion object {
        const val DEFAULT_CODE_TTL_MS = 2L * 60 * 1000
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val MAX_NAME_CHARS = 40
        const val DEFAULT_NAME = "client"
        const val CODE_DIGITS = 6

        private val random = SecureRandom()

        fun randomCode(): String = buildString { repeat(CODE_DIGITS) { append(random.nextInt(10)) } }
    }
}
