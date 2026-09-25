package com.druk.lmplayground.cloud

import org.json.JSONObject

/**
 * Parses OpenAI-compatible chat.completion.chunk SSE payloads.
 * Never logs the Authorization header or prompt bodies.
 */
object CloudSseParser {
    /**
     * @return text delta for this `data:` line, empty string for role-only /
     * empty chunks, null when the stream should end (`[DONE]`).
     */
    fun deltaFromDataLine(data: String): String? {
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed == "[DONE]") return null
        return try {
            val root = JSONObject(trimmed)
            val choices = root.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val choice = choices.optJSONObject(0) ?: return ""
            val delta = choice.optJSONObject("delta") ?: return ""
            if (!delta.has("content") || delta.isNull("content")) return ""
            delta.optString("content", "")
        } catch (_: Exception) {
            ""
        }
    }

    fun usageFromDataLine(data: String): CloudUsage? {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return null
        return try {
            val root = JSONObject(trimmed)
            val usage = root.optJSONObject("usage") ?: return null
            CloudUsage(
                promptTokens = usage.optIntOrNull("prompt_tokens"),
                completionTokens = usage.optIntOrNull("completion_tokens"),
                costUsd = usage.optDoubleOrNull("cost"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun mapHttpCode(code: Int, bodySnippet: String): CloudError = when (code) {
        401 -> CloudError.Unauthorized()
        402 -> CloudError.PaymentRequired()
        429 -> CloudError.RateLimited()
        else -> CloudError.Http(code, bodySnippet.take(200).ifBlank { "HTTP $code" })
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null
}
