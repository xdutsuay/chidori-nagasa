package com.druk.lmplayground.cloud

import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible chat request bodies, including tool calls and screenshot parts. */
object CloudPayload {

    fun messagesJson(messages: List<CloudChatMessage>): JSONArray = JSONArray().apply {
        for (m in messages) put(messageJson(m))
    }

    fun requestJson(model: String, messages: List<CloudChatMessage>, toolsJson: String?): JSONObject =
        JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", messagesJson(messages))
            val tools = toolsJson?.trim().orEmpty()
            if (tools.isNotEmpty() && tools != "[]") put("tools", JSONArray(tools))
        }

    private fun messageJson(m: CloudChatMessage): JSONObject {
        val obj = JSONObject().put("role", m.role)
        when {
            m.role == "assistant" && !m.toolCallsJson.isNullOrBlank() -> {
                obj.put("content", m.content.ifBlank { null } ?: JSONObject.NULL)
                obj.put("tool_calls", toolCalls(m.toolCallsJson))
            }
            m.role == "tool" -> {
                obj.put("tool_call_id", m.toolCallId.orEmpty())
                obj.put("content", m.content)
            }
            m.imagePngBase64 != null -> obj.put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.content))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:image/png;base64,${m.imagePngBase64}"),
                        ),
                    ),
            )
            else -> obj.put("content", m.content)
        }
        return obj
    }

    private fun toolCalls(json: String): JSONArray {
        val calls = JSONArray(json)
        return JSONArray().apply {
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                put(
                    JSONObject()
                        .put("id", c.optString("id"))
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", c.optString("name"))
                                .put("arguments", c.optString("arguments", "{}")),
                        ),
                )
            }
        }
    }
}
