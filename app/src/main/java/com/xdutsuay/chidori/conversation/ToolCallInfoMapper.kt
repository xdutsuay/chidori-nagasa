package com.xdutsuay.chidori.conversation

import com.xdutsuay.chidori.tools.ToolRegistry
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
        val durationMap = mutableMapOf<String, Long>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val id = r.getString("id")
            resultMap[id] = r.getString("content")
            if (r.has(ToolRegistry.DURATION_MS)) durationMap[id] = r.getLong(ToolRegistry.DURATION_MS)
        }
        val count = calls.length().coerceAtLeast(1)
        val perCallMs = totalDurationMs / count
        return (0 until calls.length()).map { i ->
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            val result = resultMap[id] ?: ""
            ToolCallInfo(
                name = call.getString("name"),
                arguments = call.getString("arguments"),
                result = result,
                durationMs = durationMap[id] ?: perCallMs,
                stopped = isStoppedResult(result),
                exitCode = parseExitCode(result),
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

    /** `exit_code` from cos_exec / cos_gui JSON, or null if absent / non-JSON. */
    fun parseExitCode(result: String?): Int? {
        if (result.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(result) }.getOrNull() ?: return null
        if (!obj.has("exit_code")) return null
        return obj.optInt("exit_code")
    }
}
