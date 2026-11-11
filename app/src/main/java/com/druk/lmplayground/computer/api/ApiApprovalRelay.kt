package com.druk.lmplayground.computer.api

import com.druk.lmplayground.tools.CosConfirmGate

/**
 * Mirrors the pending Computer API approval of a [CosConfirmGate] onto a system notification
 * with Allow / Deny actions, so a request can be answered while the app is in the background.
 *
 * Feed every value of the gate's `pending` flow to [onPending]. Only requests whose `toolName`
 * starts with [ToolComputerControl.APPROVAL_PREFIX] are relayed; chat tool confirmations are not.
 *
 * - A relayed request gets a fresh [Request.key] from [newKey] (never the gate's sequential id, so
 *   a notification left over from an earlier process cannot match a new request) and is shown
 *   with [Poster.show]. Seeing the same gate id again is a no-op (no second show, same key).
 * - When the gate moves on — `null`, a non-API request, or a different gate id (answered in-app,
 *   timed out, cancelled) — the previous request's notification is removed with [Poster.cancel]
 *   and its key stops working.
 * - [answer] with the current key removes the notification, calls [resolve] with that request's
 *   gate id and the decision exactly once, and returns true. Any other key (unknown, stale, already
 *   answered, blank) does nothing and returns false: no [resolve], no [Poster.cancel].
 * - [sharingStopped] (no Computer API session is shared any more) removes the current request's
 *   notification and denies it via `resolve(gateId, false)`; with nothing current it is a no-op.
 * - Denial is the default on errors: if [Poster.show] or [Poster.cancel] throws, the exception is
 *   swallowed, nothing is approved, and the relay keeps working (the in-app card still answers).
 * - All methods are thread-safe; [resolve] and [Poster] calls never happen twice for one request.
 */
class ApiApprovalRelay(
    private val resolve: (gateId: Long, approved: Boolean) -> Unit,
    private val poster: Poster,
    private val newKey: () -> String = ComputerApiShare::randomToken,
) {

    /** One relayed approval request; [key] is what the notification actions carry. */
    data class Request(val key: String, val gateId: Long, val action: String, val summary: String)

    /** Shows / removes the notification for a [Request]. */
    interface Poster {
        fun show(request: Request)
        fun cancel(request: Request)
    }

    private val lock = Any()
    private var current: Request? = null

    /** The request currently shown, or null. */
    fun current(): Request? = synchronized(lock) { current }

    /** Call with each value of the gate's `pending` flow, in order. */
    fun onPending(pending: CosConfirmGate.Pending?) {
        val api = pending?.takeIf(::isApiRequest)
        synchronized(lock) {
            val prev = current
            if (api != null && prev?.gateId == api.id) return
            current = api?.let { Request(newKey(), it.id, actionOf(it), it.summary) }
            prev?.let { safely { poster.cancel(it) } }
            current?.let { safely { poster.show(it) } }
        }
    }

    /** A notification action for [key]; returns true only if it answered the current request. */
    fun answer(key: String?, approved: Boolean): Boolean {
        if (key.isNullOrBlank()) return false
        val req = synchronized(lock) {
            val c = current?.takeIf { it.key == key } ?: return false
            current = null
            safely { poster.cancel(c) }
            c
        }
        resolve(req.gateId, approved)
        return true
    }

    /** No Computer API session is shared: drop and deny the current request, if any. */
    fun sharingStopped() {
        val req = synchronized(lock) {
            val c = current ?: return
            current = null
            safely { poster.cancel(c) }
            c
        }
        resolve(req.gateId, false)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** True for confirmations raised by [ToolComputerControl.approve]. */
        fun isApiRequest(pending: CosConfirmGate.Pending): Boolean =
            pending.toolName.startsWith(ToolComputerControl.APPROVAL_PREFIX)

        /** The action name without [ToolComputerControl.APPROVAL_PREFIX]. */
        fun actionOf(pending: CosConfirmGate.Pending): String =
            pending.toolName.removePrefix(ToolComputerControl.APPROVAL_PREFIX)
    }
}
