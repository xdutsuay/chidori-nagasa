package com.druk.lmplayground.memory

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One durable memory fact. Confirm before treating as trusted profile. */
data class MemoryFact(
    val id: String,
    val text: String,
    val confirmed: Boolean,
)

/**
 * SharedPreferences-backed durable memory. Explicit put / confirm / forget only —
 * no automatic PII capture.
 */
class DurableMemoryStore(private val prefs: SharedPreferences) {

    fun putFact(id: String, text: String, confirmed: Boolean = false) {
        val next = listFacts().filterNot { it.id == id }.toMutableList()
        next.add(MemoryFact(id = id, text = text, confirmed = confirmed))
        writeAll(next)
    }

    fun getFact(id: String): MemoryFact? = listFacts().firstOrNull { it.id == id }

    fun listFacts(): List<MemoryFact> {
        val raw = prefs.getString(KEY_FACTS, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").ifBlank { return@mapNotNull null }
            MemoryFact(
                id = id,
                text = obj.optString("text"),
                confirmed = obj.optBoolean("confirmed", false),
            )
        }
    }

    fun confirmFact(id: String) {
        val fact = getFact(id) ?: return
        putFact(id = fact.id, text = fact.text, confirmed = true)
    }

    fun forgetFact(id: String) {
        writeAll(listFacts().filterNot { it.id == id })
    }

    private fun writeAll(facts: List<MemoryFact>) {
        val arr = JSONArray()
        for (f in facts) {
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("text", f.text)
                    .put("confirmed", f.confirmed),
            )
        }
        prefs.edit().putString(KEY_FACTS, arr.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "durable_memory"
        const val KEY_FACTS = "facts_json"
    }
}
