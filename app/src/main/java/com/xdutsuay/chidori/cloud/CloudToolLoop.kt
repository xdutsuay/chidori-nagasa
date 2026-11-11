package com.xdutsuay.chidori.cloud

import com.xdutsuay.chidori.conversation.StoppedToolRound
import com.xdutsuay.chidori.tools.ToolImageAttachments
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One cloud chat turn with tool calling: stream a response, run the tools it asks
 * for, send the results back, repeat until the model answers in text or
 * [maxToolRounds] tool rounds have run.
 *
 * Screenshots (`cos_screenshot`) go back as an image part on a user message. If the
 * provider rejects that request (model without image input), the round is retried
 * once without images and the tool result says the model cannot see them; later
 * rounds of the turn send no images.
 */
class CloudToolLoop(
    private val chat: (List<CloudChatMessage>) -> Flow<CloudStreamEvent>,
    private val executeTools: suspend (toolCallsJson: String) -> String,
    private val prepareImages: (resultsJson: String, vision: Boolean) -> ToolImageAttachments.Prepared,
    private val imageBytesToBase64: (ByteArray) -> String,
    private val maxToolRounds: Int = 5,
) {
    interface Listener {
        /** All visible text of the turn so far. */
        fun onDelta(fullText: String)
        fun onToolCalls(toolCallsJson: String, resultsJson: String, durationMs: Long)
    }

    data class Result(val text: String, val usage: CloudUsage?, val toolRounds: Int)

    private class Round(val text: String, val toolCallsJson: String?, val usage: CloudUsage?)

    suspend fun run(history: List<CloudChatMessage>, listener: Listener): Result {
        val messages = history.toMutableList()
        val turnText = StringBuilder()
        var usage: CloudUsage? = null
        var toolRounds = 0
        var imagesAllowed = true
        var sawToolCalls = false
        val attachedCallIds = mutableSetOf<String>()

        while (true) {
            val textBefore = turnText.length
            val round = try {
                stream(messages, turnText, listener)
            } catch (e: CloudClientException) {
                if (!imagesAllowed || messages.none { it.imagePngBase64 != null } || !isImageRejection(e.error)) throw e
                imagesAllowed = false
                turnText.setLength(textBefore)
                stripImages(messages, attachedCallIds)
                stream(messages, turnText, listener)
            }
            round.usage?.let { usage = it }
            val calls = round.toolCallsJson ?: break
            sawToolCalls = true

            if (toolRounds >= maxToolRounds) {
                val results = StoppedToolRound.complete(calls, null) { StoppedToolRound.ROUND_LIMIT_ERROR }
                listener.onToolCalls(calls, results, 0L)
                break
            }
            toolRounds++

            val started = System.currentTimeMillis()
            val results = executeTools(calls)
            val prepared = prepareImages(results, imagesAllowed)
            listener.onToolCalls(calls, prepared.resultsJson, System.currentTimeMillis() - started)

            messages += CloudChatMessage("assistant", round.text, toolCallsJson = calls)
            val entries = JSONArray(prepared.resultsJson)
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                messages += CloudChatMessage("tool", entry.optString("content"), toolCallId = entry.optString("id"))
            }
            val image = prepared.image
            val attachedId = prepared.attachedToolCallId
            if (image != null && imagesAllowed) {
                attachedId?.let { attachedCallIds += it }
                messages += CloudChatMessage(
                    "user",
                    "Screenshot returned by tool call ${attachedId.orEmpty()}.",
                    imagePngBase64 = imageBytesToBase64(image),
                )
            }
        }

        val text = turnText.toString()
        if (text.isBlank() && !sawToolCalls) throw CloudClientException(CloudError.EmptyReply())
        return Result(text, usage, toolRounds)
    }

    private suspend fun stream(
        messages: List<CloudChatMessage>,
        turnText: StringBuilder,
        listener: Listener,
    ): Round {
        val roundText = StringBuilder()
        var calls: String? = null
        var usage: CloudUsage? = null
        chat(messages.toList()).collect { event ->
            when (event) {
                is CloudStreamEvent.Delta -> {
                    roundText.append(event.text)
                    turnText.append(event.text)
                    listener.onDelta(turnText.toString())
                }
                is CloudStreamEvent.ToolCalls -> calls = event.json
                is CloudStreamEvent.Finished -> usage = event.usage
            }
        }
        return Round(roundText.toString(), calls, usage)
    }

    private fun stripImages(messages: MutableList<CloudChatMessage>, attachedCallIds: Set<String>) {
        messages.removeAll { it.imagePngBase64 != null }
        for (i in messages.indices) {
            val m = messages[i]
            if (m.role != "tool" || m.toolCallId !in attachedCallIds) continue
            val content = runCatching { JSONObject(m.content) }.getOrNull() ?: continue
            content.put("image", ToolImageAttachments.NO_VISION_NOTE)
            messages[i] = m.copy(content = content.toString())
        }
    }

    private fun isImageRejection(error: CloudError): Boolean =
        error is CloudError.Http && error.code in IMAGE_REJECTION_CODES

    private companion object {
        val IMAGE_REJECTION_CODES = setOf(400, 404, 415, 422)
    }
}
