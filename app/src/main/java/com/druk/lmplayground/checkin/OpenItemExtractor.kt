package com.druk.lmplayground.checkin

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Turns a model reply into candidate open items. A payload that is not the
 * grammar's JSON object, or that contains an item we cannot validate, is
 * dropped whole (null). A valid empty `items` array is an empty list.
 */
object OpenItemExtractor {
    data class ExtractedItem(
        val kind: OpenItemKind,
        val summary: String,
        val dueAtMs: Long?,
    )

    fun parse(raw: String?): List<ExtractedItem>? {
        if (raw.isNullOrBlank()) return null
        val json = extractObject(raw) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.has("items")) return null
        val array = root.optJSONArray("items") ?: return null
        val out = ArrayList<ExtractedItem>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: return null
            val item = parseItem(obj) ?: return null
            out += item
        }
        return out
    }

    private fun parseItem(obj: JSONObject): ExtractedItem? {
        val kind = OpenItemKind.fromStorage(obj.optString("kind")) ?: return null
        val summary = obj.optString("summary").trim()
        if (summary.isEmpty() || summary.length > 280) return null
        val due = readDue(obj)
        if (obj.has("dueAt") && due == DUE_INVALID) return null
        return ExtractedItem(kind, summary, if (due == DUE_INVALID) null else due)
    }

    private fun readDue(obj: JSONObject): Long? {
        if (!obj.has("dueAt") || obj.isNull("dueAt")) return null
        val raw = obj.opt("dueAt")
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
                ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
                ?: DUE_INVALID
            else -> DUE_INVALID
        }
    }

    private fun extractObject(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").removePrefix("json").removePrefix("JSON").trim()
            val fence = text.lastIndexOf("```")
            if (fence >= 0) text = text.substring(0, fence).trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private val DUE_INVALID = Long.MIN_VALUE
}

/** Chat commands that update the ledger without a model call. */
enum class ChatCommand {
    DONE,
    PARK,
    CLOSE,
    REMIND,
}

fun parseChatCommand(text: String): ChatCommand? {
    val normalized = text.trim()
        .lowercase()
        .trimEnd('.', '!', '?')
        .replace(Regex("\\s+"), " ")
    return when (normalized) {
        "done", "mark done", "that's done", "thats done" -> ChatCommand.DONE
        "park", "park it", "park this" -> ChatCommand.PARK
        "drop", "drop it", "drop this" -> ChatCommand.CLOSE
        "remind me", "remind me about this" -> ChatCommand.REMIND
        else -> if (normalized.startsWith("remind me ")) ChatCommand.REMIND else null
    }
}
