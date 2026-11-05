package com.druk.lmplayground.conversation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes/decodes the per-message tool rows ([ToolCallInfo]) into the JSON
 * string persisted in `ChatMessageEntity.toolCallsJson`. Without it a
 * reopened session lost tool cards / Stopped chips (Room v9 carry-over).
 *
 * Field-for-field with [ToolCallInfo]: name, arguments, result, durationMs,
 * precedingThinking, precedingThinkingDurationSeconds, precedingThinkingTokens,
 * stopped. Missing fields decode to the data-class defaults, so rows saved
 * before a field existed still load.
 */
object ToolCallInfoCodec {

    fun encode(toolCalls: List<ToolCallInfo>?): String? {
        if (toolCalls.isNullOrEmpty()) return null
        val arr = JSONArray()
        for (call in toolCalls) {
            arr.put(
                JSONObject()
                    .put("name", call.name)
                    .put("arguments", call.arguments)
                    .put("result", call.result)
                    .put("durationMs", call.durationMs)
                    .put("precedingThinking", call.precedingThinking)
                    .put("precedingThinkingDurationSeconds", call.precedingThinkingDurationSeconds)
                    .put("precedingThinkingTokens", call.precedingThinkingTokens)
                    .put("stopped", call.stopped)
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<ToolCallInfo>? {
        if (json.isNullOrBlank()) return null
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        if (arr.length() == 0) return null
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ToolCallInfo(
                name = obj.optString("name"),
                arguments = obj.optString("arguments"),
                result = obj.optString("result"),
                durationMs = obj.optLong("durationMs"),
                precedingThinking = obj.optString("precedingThinking"),
                precedingThinkingDurationSeconds = obj.optInt("precedingThinkingDurationSeconds"),
                precedingThinkingTokens = obj.optInt("precedingThinkingTokens"),
                stopped = obj.optBoolean("stopped"),
            )
        }
    }
}
