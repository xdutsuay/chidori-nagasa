package com.druk.lmplayground.checkin

import com.druk.lmplayground.cloud.CloudChatMessage
import com.druk.lmplayground.cloud.CloudClient
import com.druk.lmplayground.cloud.CloudProviderEntity
import com.druk.lmplayground.cloud.CloudStreamEvent
import com.druk.lmplayground.conversation.GenerationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One completion used for extraction or for wording a nudge. Null means
 * "no model" (queue or drop), not "empty JSON".
 */
fun interface CheckInCompleter {
    suspend fun complete(prompt: String): String?
}

/**
 * Local model by default. Uses the loaded runtime's node-mode one-shot,
 * which opens a temporary session and leaves the chat session alone.
 * Returns null when nothing is loaded, the engine is busy, or generation fails.
 */
class LocalCheckInCompleter : CheckInCompleter {
    override suspend fun complete(prompt: String): String? {
        val runtime = LoadedModelSlot.runtime ?: return null
        val params = GenerationParams(
            contextSize = 2048,
            temperature = 0.2f,
            topP = 0.9f,
            repetitionPenalty = 1.05f,
            topK = 40,
            minP = 0.05f,
            seed = 1,
            thinkingBudget = 0,
        )
        return try {
            withTimeoutOrNull(ONE_SHOT_TIMEOUT_MS) {
                runtime.nodeModeComplete(prompt, params).getOrNull()?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ONE_SHOT_TIMEOUT_MS = 20_000L
    }
}

/**
 * Cloud completion. Constructed only as the fallback inside
 * [RoutingCheckInCompleter], and that fallback runs only when the user has
 * already enabled hybrid/cloud.
 */
class CloudCheckInCompleter(
    private val provider: suspend () -> CloudProviderEntity?,
    private val apiKey: (String) -> String?,
    private val client: CloudClient,
) : CheckInCompleter {
    override suspend fun complete(prompt: String): String? {
        val chosen = provider() ?: return null
        val key = apiKey(chosen.id)?.takeIf { it.isNotBlank() } ?: return null
        val model = chosen.defaultModel.takeIf { it.isNotBlank() } ?: return null
        val text = StringBuilder()
        return try {
            withTimeoutOrNull(ONE_SHOT_TIMEOUT_MS) {
                client.streamChat(
                    chosen.baseUrl,
                    key,
                    model,
                    listOf(CloudChatMessage(role = "user", content = prompt)),
                ).collect { event ->
                    if (event is CloudStreamEvent.Delta) text.append(event.text)
                }
                text.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ONE_SHOT_TIMEOUT_MS = 20_000L
    }
}

/**
 * Local first. Cloud only when [hybridEnabled] is already true and the local
 * attempt returned nothing. [hybridEnabled] must not flip itself on.
 */
class RoutingCheckInCompleter(
    private val local: CheckInCompleter,
    private val cloud: CheckInCompleter,
    private val hybridEnabled: () -> Boolean,
) : CheckInCompleter {
    override suspend fun complete(prompt: String): String? {
        local.complete(prompt)?.let { return it }
        if (!hybridEnabled()) return null
        return cloud.complete(prompt)
    }
}
