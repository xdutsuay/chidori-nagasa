package com.xdutsuay.chidori.conversation

import com.xdutsuay.chidori.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one batch of (blocking) tool calls on IO so chat Stop can interrupt it.
 *
 * On cancellation it calls [ToolRegistry.cancelInFlight] (web requests aborted;
 * `cos_exec` cancels its coroutine, so proot is terminated via ProotTerminator with
 * reason `chat-stop`), re-issuing it every [pollMs] until the batch returns, so a
 * tool that registers its work just after the first cancel is still caught. The
 * wait is bounded by the tools' own cancel paths (proot: SIGQUIT + 2 s grace +
 * force kill). The batch's results, completed with a cancelled entry for every
 * call that has none (see [StoppedToolRound.complete]), are always handed to
 * [onCancelledResults] before the [CancellationException] is rethrown, so the
 * caller can record them in the session history and on the tool card.
 */
internal object ToolCallRunner {
    const val CANCEL_POLL_MS = 200L

    suspend fun run(
        registry: ToolRegistry,
        toolCallsJson: String,
        pollMs: Long = CANCEL_POLL_MS,
        onCancelledResults: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        // Captured outside the Deferred: a cancelled async ends Cancelled even if
        // its blocking body returns a value afterwards.
        val finished = AtomicReference<String?>(null)
        val exec = async { registry.executeToolCalls(toolCallsJson).also { finished.set(it) } }
        try {
            exec.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                registry.cancelInFlight()
                while (withTimeoutOrNull(pollMs) { exec.join() } == null) {
                    registry.cancelInFlight()
                }
                val results = StoppedToolRound.complete(toolCallsJson, finished.get())
                runCatching { onCancelledResults(results) }
            }
            throw e
        }
    }
}
