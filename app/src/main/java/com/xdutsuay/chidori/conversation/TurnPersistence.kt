package com.xdutsuay.chidori.conversation

import kotlinx.coroutines.CancellationException

/**
 * Ordering rules for saving a chat turn, kept out of [ConversationViewModel]
 * so they can be unit-tested without the native runtime.
 *
 * - The assistant reply is persisted before "Response ready" is announced, and
 *   a failed save is reported instead of swallowed.
 * - A failed user-message save ends the turn instead of leaving it generating.
 * - An attached image is only persisted once the message is known to fit.
 */
internal object TurnPersistence {

    /**
     * Runs [persist], then [announce] exactly once. A non-cancellation failure
     * is passed to [onSaveFailed] before announcing; returns whether the save
     * succeeded. Cancellation propagates without announcing.
     */
    suspend fun finishAssistantTurn(
        persist: suspend () -> Unit,
        announce: () -> Unit,
        onSaveFailed: (Throwable) -> Unit,
    ): Boolean {
        val saved = try {
            persist()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onSaveFailed(t)
            false
        }
        announce()
        return saved
    }

    /**
     * Persists the user's message and returns its session id, or null after
     * reporting a non-cancellation failure to [onFailed].
     */
    suspend fun beginUserTurn(
        persistUser: suspend () -> String,
        onFailed: (Throwable) -> Unit,
    ): String? = try {
        persistUser()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        onFailed(t)
        null
    }

    /**
     * Checks the UTF-16 payload size ([contentChars] × 2) against
     * [maxPayloadBytes] before calling [persistImage], so an oversized message
     * never writes an image file.
     */
    fun <T> gateImage(contentChars: Int, maxPayloadBytes: Int, persistImage: () -> T?): ImageGate<T> {
        if (contentChars.toLong() * 2 > maxPayloadBytes) return ImageGate.TooLarge
        return ImageGate.Fits(persistImage())
    }
}

internal sealed class ImageGate<out T> {
    object TooLarge : ImageGate<Nothing>()
    data class Fits<T>(val image: T?) : ImageGate<T>()
}
