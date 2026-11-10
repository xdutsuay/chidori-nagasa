package com.druk.lmplayground.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Review-before-act gate (CP7 F4): a tool that needs confirmation blocks its
 * (IO) thread in [await] while the chat shows a confirm card bound to [pending].
 * Only an explicit approve returns true; deny, timeout and Stop ([cancelAll])
 * all return false so the tool is not run.
 */
class CosConfirmGate(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    data class Pending(val id: Long, val toolName: String, val summary: String)

    private val ids = AtomicLong(0)
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    private var waiting: Pair<Long, CompletableFuture<Boolean>>? = null
    private val lock = Any()

    fun await(toolName: String, summary: String): Boolean {
        val id = ids.incrementAndGet()
        val answer = CompletableFuture<Boolean>()
        synchronized(lock) {
            waiting?.second?.complete(false)
            waiting = id to answer
            _pending.value = Pending(id, toolName, summary)
        }
        return try {
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            synchronized(lock) {
                if (waiting?.first == id) {
                    waiting = null
                    _pending.value = null
                }
            }
        }
    }

    fun resolve(id: Long, approved: Boolean) {
        synchronized(lock) {
            val (waitingId, answer) = waiting ?: return
            if (waitingId != id) return
            answer.complete(approved)
        }
    }

    fun cancelAll() {
        synchronized(lock) { waiting?.second?.complete(false) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Process-wide gate shared by the COS tools and the chat confirm card. */
        val shared = CosConfirmGate()
    }
}
