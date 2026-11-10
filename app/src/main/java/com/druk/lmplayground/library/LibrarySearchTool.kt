package com.druk.lmplayground.library

import com.druk.lmplayground.tools.Tool
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** `library_search`: finds passages in the user's Library (files and folders added on the Library screen). */
class LibrarySearchTool(
    private val search: (query: String, limit: Int) -> List<LibraryHit>,
    private val hasDocuments: () -> Boolean,
) : Tool {
    override val name = NAME
    override val description =
        "Search the user's own documents (files and folders they added to the Library) and return the best " +
            "matching passages with their file name and part number. Use it when the user asks about their " +
            "notes, files or documents, then answer from the passages and cite them as (file name, part N)."
    override val parametersSchema =
        """{"type":"object","properties":{"query":{"type":"string","description":"Keywords or a question about the user's documents"},"max_results":{"type":"integer","description":"Maximum number of passages (default $DEFAULT_RESULTS, max $MAX_RESULTS)"}},"required":["query"]}"""

    override fun isAvailable(): Boolean = hasDocuments()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (e: JSONException) {
            return error("invalid arguments: ${e.message}")
        }
        val query = args.optString("query").trim()
        if (query.isEmpty()) return error("query is required")
        if (!hasDocuments()) return error("The Library is empty. Ask the user to add files or folders on the Library screen.")
        val limit = args.optInt("max_results", DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)
        val hits = try {
            search(query, limit)
        } catch (e: Exception) {
            return error("search failed: ${e.message}")
        }
        val results = JSONArray()
        hits.forEach { hit ->
            results.put(JSONObject().put("source", hit.docName).put("part", hit.part).put("text", hit.text))
        }
        val out = JSONObject().put("results", results)
        if (hits.isEmpty()) out.put("note", "No passages matched. Try other keywords.")
        return out.toString()
    }

    private fun error(message: String) = JSONObject().put("error", message).toString()

    companion object {
        const val NAME = "library_search"
        const val DEFAULT_RESULTS = 5
        const val MAX_RESULTS = 10
    }
}
