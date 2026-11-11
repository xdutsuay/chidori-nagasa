package com.xdutsuay.chidori.cloud

import org.json.JSONArray
import org.json.JSONObject

/** One streamed fragment of `choices[0].delta.tool_calls[n]`. */
data class CloudToolCallDelta(
    val index: Int,
    val id: String?,
    val name: String?,
    val argumentsChunk: String?,
)

/**
 * Reassembles streamed tool-call fragments (id + name first, arguments split over
 * many chunks) into the `[{"id","name","arguments"}]` shape
 * [com.xdutsuay.chidori.tools.ToolRegistry.executeToolCalls] reads.
 */
class CloudToolCallAccumulator {
    private class Partial(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder())

    private val calls = sortedMapOf<Int, Partial>()

    fun add(delta: CloudToolCallDelta) {
        val p = calls.getOrPut(delta.index) { Partial() }
        delta.id?.takeIf { it.isNotEmpty() }?.let { p.id = it }
        delta.name?.takeIf { it.isNotEmpty() }?.let { p.name = it }
        delta.argumentsChunk?.let { p.args.append(it) }
    }

    fun isEmpty(): Boolean = calls.isEmpty()

    fun toJson(): String {
        val arr = JSONArray()
        for ((index, p) in calls) {
            arr.put(
                JSONObject()
                    .put("id", p.id ?: "call_$index")
                    .put("name", p.name.orEmpty())
                    .put("arguments", p.args.toString().ifBlank { "{}" }),
            )
        }
        return arr.toString()
    }
}
