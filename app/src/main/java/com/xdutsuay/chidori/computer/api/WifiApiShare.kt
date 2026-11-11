package com.xdutsuay.chidori.computer.api

import com.xdutsuay.chidori.computer.desktop.DesktopBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class WifiEndpoint(
    val host: String,
    val port: Int,
    val publicKeyPin: String,
    val certificateSha256: String,
    val expiresAtMs: Long,
    val control: Boolean,
)

sealed interface WifiShareState {
    data object Off : WifiShareState
    data class Shared(val endpoint: WifiEndpoint) : WifiShareState
    data class Error(val message: String) : WifiShareState
}

/**
 * Wi-Fi Computer API session (slice 3): HTTPS only, paired clients only.
 *
 * - [start]: [wifiAddress] null → [WifiShareState.Error] ("not on Wi-Fi") and a failed Result,
 *   nothing listening. `allowControl` with a null [control] → Error and failure. Otherwise starts a
 *   [ComputerApiServer] on that address and [port] with [identity]'s TLS context and routes that
 *   accept only tokens from [pairing] (no static token), issues a pairing code, and moves to
 *   [WifiShareState.Shared] with the identity's pin/fingerprint and
 *   `expiresAtMs = nowMs() + maxMs`. While shared, [start] returns the current endpoint.
 * - The session ends ([WifiShareState.Off], server stopped, [PairingRegistry.revokeAll]) on [stop],
 *   after [maxMs], or after [idleMs] without a successful (2xx) response. Every 2xx resets the
 *   idle timer. [stop] when off is a no-op.
 * - [pairing] is shared across sessions; codes and clients from an ended session do not work in the next.
 * - [pairingUri] is the [PairingUri] for the current endpoint and code, or null when not shared or
 *   when there is no current code.
 */
class WifiApiShare(
    private val scope: CoroutineScope,
    private val bridge: () -> DesktopBridge?,
    private val identity: () -> TlsIdentity,
    private val wifiAddress: () -> String?,
    private val port: Int = DEFAULT_PORT,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val control: ComputerControl? = null,
    val pairing: PairingRegistry = PairingRegistry(nowMs = nowMs),
    private val maxMs: Long = DEFAULT_MAX_MS,
    private val idleMs: Long = DEFAULT_IDLE_MS,
) {
    private val _state = MutableStateFlow<WifiShareState>(WifiShareState.Off)
    val state: StateFlow<WifiShareState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var server: ComputerApiServer? = null
    private var jobs: List<Job> = emptyList()
    private val lastSuccessMs = AtomicLong(0)

    suspend fun start(allowControl: Boolean = false): Result<WifiEndpoint> = mutex.withLock {
        (_state.value as? WifiShareState.Shared)?.let { return Result.success(it.endpoint) }
        val host = wifiAddress() ?: return fail(NOT_ON_WIFI)
        if (allowControl && control == null) return fail("Computer API control is not available")
        pairing.revokeAll()
        val id = try {
            identity()
        } catch (e: Exception) {
            return fail("TLS identity unavailable: ${e.message}")
        }
        val routes = ComputerApiRoutes(token = "", control = if (allowControl) control else null, pairing = pairing, bridge = bridge)
        val s = ComputerApiServer(routes, host, port, id.serverSslContext()) { status ->
            if (status in 200..299) lastSuccessMs.set(nowMs())
        }
        val bound = try {
            s.start()
        } catch (e: Exception) {
            s.stop()
            return fail("Computer API did not start on Wi-Fi: ${e.message}")
        }
        val now = nowMs()
        lastSuccessMs.set(now)
        val endpoint = WifiEndpoint(host, bound, id.publicKeyPin, id.certificateSha256, now + maxMs, allowControl)
        server = s
        pairing.issueCode()
        _state.value = WifiShareState.Shared(endpoint)
        jobs = listOf(
            scope.launch {
                delay(maxMs)
                stop()
            },
            scope.launch {
                while (true) {
                    val remaining = lastSuccessMs.get() + idleMs - nowMs()
                    if (remaining <= 0) break
                    delay(remaining)
                }
                stop()
            },
        )
        Result.success(endpoint)
    }

    suspend fun stop() {
        val old: List<Job>
        mutex.withLock {
            val s = server ?: return
            server = null
            old = jobs
            jobs = emptyList()
            s.stop()
            pairing.revokeAll()
            _state.value = WifiShareState.Off
        }
        val self = coroutineContext[Job]
        old.filter { it !== self }.forEach { it.cancel() }
    }

    fun pairingUri(): String? {
        val e = (_state.value as? WifiShareState.Shared)?.endpoint ?: return null
        val c = pairing.code.value ?: return null
        return PairingUri.build(PairingUri.Fields(e.host, e.port, e.publicKeyPin, c.code))
    }

    private fun fail(message: String): Result<WifiEndpoint> {
        _state.value = WifiShareState.Error(message)
        return Result.failure(IllegalStateException(message))
    }

    companion object {
        const val DEFAULT_PORT = 7902
        const val DEFAULT_MAX_MS = 30L * 60 * 1000
        const val DEFAULT_IDLE_MS = 5L * 60 * 1000
        const val NOT_ON_WIFI = "not on Wi-Fi"
    }
}
