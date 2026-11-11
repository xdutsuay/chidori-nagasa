package com.druk.lmplayground.computer.api

import com.druk.lmplayground.computer.desktop.DesktopBridge
import com.druk.lmplayground.computer.desktop.ShareLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

data class ApiEndpoint(
    val host: String,
    val port: Int,
    val token: String,
    val expiresAtMs: Long,
    val control: Boolean = false,
)

sealed interface ApiShareState {
    data object Off : ApiShareState
    data class Shared(val endpoint: ApiEndpoint) : ApiShareState
    data class Error(val message: String) : ApiShareState
}

/**
 * One Computer API session (slice 1: loopback only, read-only).
 *
 * - [start] makes a fresh [token] per session, starts a [ComputerApiServer] on 127.0.0.1:[port]
 *   and moves to [ApiShareState.Shared] with `expiresAtMs = nowMs() + lease.maxMs`. Calling
 *   [start] while shared returns the current endpoint unchanged (same token).
 * - If the server cannot bind → [ApiShareState.Error] and a failed Result.
 * - The session ends ([ApiShareState.Off], server stopped, port released) on [stop] or when the
 *   lease runs out (a delay of `lease.maxMs` on [scope]). [stop] when off is a no-op.
 * - After a stop, the old token is rejected because the server is gone; the next [start] gets a new one.
 *
 * Slice 2: `start(lease, allowControl = true)` serves [control] through the routes and sets
 * [ApiEndpoint.control]; with `allowControl = false` (default) the routes get no control, so
 * exec/gui answer 403. `allowControl = true` with a null [control] → [ApiShareState.Error] and
 * a failed Result, nothing started. While shared, [start] returns the current endpoint unchanged
 * whatever `allowControl` is (stop first to change it).
 */
class ComputerApiShare(
    private val scope: CoroutineScope,
    private val bridge: () -> DesktopBridge?,
    private val port: Int = ComputerApiServer.DEFAULT_PORT,
    private val token: () -> String = ::randomToken,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val control: ComputerControl? = null,
) {
    private val _state = MutableStateFlow<ApiShareState>(ApiShareState.Off)
    val state: StateFlow<ApiShareState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var server: ComputerApiServer? = null
    private var leaseJob: Job? = null

    suspend fun start(
        lease: ShareLease = ShareLease(),
        allowControl: Boolean = false,
    ): Result<ApiEndpoint> = mutex.withLock {
        (_state.value as? ApiShareState.Shared)?.let { return Result.success(it.endpoint) }
        if (allowControl && control == null) {
            val msg = "Computer API control is not available"
            _state.value = ApiShareState.Error(msg)
            return Result.failure(IllegalStateException(msg))
        }
        val t = token()
        val routes = ComputerApiRoutes(t, if (allowControl) control else null, bridge)
        val s = ComputerApiServer(routes, ComputerApiServer.LOOPBACK, port)
        val bound = try {
            s.start()
        } catch (e: Exception) {
            s.stop()
            val msg = "Computer API did not start: ${e.message}"
            _state.value = ApiShareState.Error(msg)
            return Result.failure(IllegalStateException(msg, e))
        }
        val endpoint = ApiEndpoint(ComputerApiServer.LOOPBACK, bound, t, nowMs() + lease.maxMs, allowControl)
        server = s
        _state.value = ApiShareState.Shared(endpoint)
        leaseJob = scope.launch {
            delay(lease.maxMs)
            stop()
        }
        Result.success(endpoint)
    }

    suspend fun stop() {
        val job: Job?
        mutex.withLock {
            val s = server ?: return
            server = null
            job = leaseJob
            leaseJob = null
            s.stop()
            _state.value = ApiShareState.Off
        }
        if (job != null && job !== kotlin.coroutines.coroutineContext[Job]) job.cancel()
    }

    companion object {
        private const val TOKEN_BYTES = 32
        private val random = SecureRandom()

        /** 32 random bytes from SecureRandom as 64 lowercase hex characters. */
        fun randomToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
