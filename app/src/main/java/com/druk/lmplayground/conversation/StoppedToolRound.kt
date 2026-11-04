package com.druk.lmplayground.conversation

import com.druk.lmplayground.tools.CosExecFormatter
import com.druk.lmplayground.tools.CosToolDefaults
import com.druk.lmplayground.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Closes a tool round that will not get a model response (chat Stop, or the
 * tool-round limit), so the session history never ends on an orphan tool call.
 *
 * Why: the native session appends the assistant's tool-call message as soon as
 * the model emits it. If the turn ends without [submit]ting results, the next
 * user message follows an unanswered tool call and small models (seen with
 * LFM2.5 350M) answer it by inventing a tool result such as
 * `{"exit_code":0,"stdout":"","stderr":""}`. Recording the results here (without
 * generating) puts the real outcome, e.g. a stopped `cos_exec` with exit_code
 * 130 and `cancelled:true`, into the history the next turn is rendered from, and
 * shows the same results on the tool card.
 */
internal object StoppedToolRound {

    /** Result for a call that never ran because the turn hit the tool-round limit. */
    const val ROUND_LIMIT_ERROR =
        """{"error":"not run: tool-call round limit reached for this message"}"""

    /** Cancelled content for a call that has no result yet (Stop before or while it ran). */
    fun cancelledContentFor(toolName: String): String =
        if (toolName == CosToolDefaults.COS_EXEC) CosExecFormatter.cancelledJson()
        else ToolRegistry.CANCELLED_ERROR

    /**
     * Returns a `[{id,name,content}]` array with one entry per call in
     * [toolCallsJson], in call order: the entry from [resultsJson] when present,
     * otherwise [missing] (cancelled by default). [resultsJson] may be null or
     * malformed (the batch never returned).
     */
    fun complete(
        toolCallsJson: String,
        resultsJson: String?,
        missing: (String) -> String = ::cancelledContentFor,
    ): String {
        val byId = mutableMapOf<String, JSONObject>()
        if (resultsJson != null) {
            runCatching {
                val arr = JSONArray(resultsJson)
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    byId[r.getString("id")] = r
                }
            }
        }
        val calls = runCatching { JSONArray(toolCallsJson) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val id = call.getString("id")
            val name = call.optString("name")
            val content = byId[id]?.optString("content")?.takeIf { it.isNotEmpty() } ?: missing(name)
            out.put(JSONObject().put("id", id).put("name", name).put("content", content))
        }
        return out.toString()
    }

    /**
     * Record [resultsJson] for a round that ends here: [show] the tool card
     * (UI) and [submit] the results into the session history without generating.
     * Each side is attempted even if the other throws. Returns the [submit] rc,
     * or null if it threw.
     */
    fun record(
        toolCallsJson: String,
        resultsJson: String,
        durationMs: Long,
        submit: (String) -> Int,
        show: (List<ToolCallInfo>) -> Unit,
        log: (String) -> Unit = {},
    ): Int? {
        runCatching {
            show(ToolCallInfoMapper.buildToolCallInfoList(toolCallsJson, resultsJson, durationMs))
        }.onFailure { log("stopped tool round: UI update failed: $it") }
        return runCatching { submit(resultsJson) }
            .onSuccess { log("stopped tool round: results recorded in history (rc=$it)") }
            .onFailure { log("stopped tool round: recording results failed: $it") }
            .getOrNull()
    }
}
