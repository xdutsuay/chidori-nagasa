package com.druk.lmplayground.conversation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps the native layer's tool-call / tool-result JSON payloads into the
 * [ToolCallInfo] list shown on the assistant message.
 */
object ToolCallInfoMapper {

    fun buildToolCallInfoList(
        toolCallsJson: String,
        toolResultsJson: String,
        totalDurationMs: Long,
    ): List<ToolCallInfo> {
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray(toolResultsJson)
        val resultMap = mutableMapOf<String, String>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            resultMap[r.getString("id")] = r.getString("content")
        }
        val count = calls.length().coerceAtLeast(1)
        val perCallMs = totalDurationMs / count
        return (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            ToolCallInfo(
                name = call.getString("name"),
                arguments = call.getString("arguments"),
                result = resultMap[id] ?: "",
                durationMs = perCallMs,
                stopped = isStoppedResult(resultMap[id]),
            )
        }
    }

    /**
     * True when a tool result says the call was stopped by the user: `cancelled:
     * true` (cos_exec) or an `error` starting with "cancelled:" (skipped calls).
     */
    fun isStoppedResult(result: String?): Boolean {
        if (result.isNullOrBlank()) return false
        val obj = runCatching { JSONObject(result) }.getOrNull() ?: return false
        return obj.optBoolean("cancelled", false) ||
            obj.optString("error").startsWith("cancelled:")
    }
}
