package com.xdutsuay.chidori.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LibrarySearchTool] with fake lambdas (plain JVM).
 *
 * Covers: tool name/schema contract, isAvailable mirroring hasDocuments,
 * argument validation (missing/blank query, malformed JSON -> error, never an
 * exception), the "Library is empty" guard, hit mapping to
 * {"source","part","text"} in order, and max_results default 5 coerced 1..10
 * and passed through to the search lambda together with the raw query.
 */
class LibrarySearchToolTest {

    // ---- fake plumbing ----

    private class CapturedSearch(
        var query: String? = null,
        var limit: Int? = null,
    )

    private fun toolWithHits(
        hits: List<LibraryHit>,
        hasDocuments: Boolean = true,
        captured: CapturedSearch? = null,
    ): LibrarySearchTool =
        LibrarySearchTool(
            search = { query, limit ->
                captured?.query = query
                captured?.limit = limit
                hits
            },
            hasDocuments = { hasDocuments },
        )

    private val sampleHits = listOf(
        LibraryHit(docName = "a.txt", part = 2, text = "second part", score = 7),
        LibraryHit(docName = "b.txt", part = 1, text = "first part of b", score = 3),
    )

    // ---- identity / schema ----

    @Test
    fun `tool name is library_search`() {
        assertEquals("library_search", toolWithHits(emptyList()).name)
    }

    @Test
    fun `parametersSchema is valid JSON with required query and optional integer max_results`() {
        val schema = JSONObject(toolWithHits(emptyList()).parametersSchema)
        assertEquals("object", schema.getString("type"))
        val required = schema.getJSONArray("required")
        val requiredNames = (0 until required.length()).map { required.getString(it) }
        assertTrue("required must contain 'query'", requiredNames.contains("query"))
        val props = schema.getJSONObject("properties")
        assertTrue("properties must define 'query'", props.has("query"))
        assertTrue("properties must define optional 'max_results'", props.has("max_results"))
        assertEquals("integer", props.getJSONObject("max_results").getString("type"))
        assertFalse("max_results must not be required", requiredNames.contains("max_results"))
    }

    @Test
    fun `description is non-blank`() {
        assertTrue(toolWithHits(emptyList()).description.isNotBlank())
    }

    // ---- isAvailable ----

    @Test
    fun `isAvailable mirrors hasDocuments`() {
        assertTrue(toolWithHits(emptyList(), hasDocuments = true).isAvailable())
        assertFalse(toolWithHits(emptyList(), hasDocuments = false).isAvailable())
    }

    // ---- argument validation ----

    @Test
    fun `missing query returns error`() {
        val out = JSONObject(toolWithHits(emptyList()).execute("{}"))
        assertTrue(out.has("error"))
        assertFalse(out.has("results"))
    }

    @Test
    fun `blank query returns error`() {
        val out = JSONObject(toolWithHits(emptyList()).execute("""{"query":"   "}"""))
        assertTrue(out.has("error"))
        assertFalse(out.has("results"))
    }

    @Test
    fun `malformed JSON arguments return error without throwing`() {
        val out = JSONObject(toolWithHits(emptyList()).execute("{not json"))
        assertTrue(out.has("error"))
    }

    // ---- empty library guard ----

    @Test
    fun `no documents returns error mentioning the Library is empty`() {
        val out = JSONObject(
            LibrarySearchTool(
                search = { _, _ -> throw AssertionError("search must not run on an empty library") },
                hasDocuments = { false },
            ).execute("""{"query":"anything"}"""),
        )
        assertTrue(out.has("error"))
        assertTrue(
            "error should mention the library is empty: ${out.getString("error")}",
            out.getString("error").contains("empty", ignoreCase = true),
        )
        assertFalse(out.has("results"))
    }

    // ---- results mapping ----

    @Test
    fun `no hits yields empty results array`() {
        val out = JSONObject(toolWithHits(emptyList()).execute("""{"query":"zebra"}"""))
        assertTrue("no-hit response must not carry an error", !out.has("error"))
        val results = out.getJSONArray("results")
        assertEquals(0, results.length())
    }

    @Test
    fun `hits map to source part text in the given order`() {
        val out = JSONObject(toolWithHits(sampleHits).execute("""{"query":"zebra"}"""))
        val results = out.getJSONArray("results")
        assertEquals(2, results.length())
        val first = results.getJSONObject(0)
        assertEquals("a.txt", first.getString("source"))
        assertEquals(2, first.getInt("part"))
        assertEquals("second part", first.getString("text"))
        val second = results.getJSONObject(1)
        assertEquals("b.txt", second.getString("source"))
        assertEquals(1, second.getInt("part"))
        assertEquals("first part of b", second.getString("text"))
    }

    // ---- max_results plumbing ----

    @Test
    fun `max_results defaults to 5 and query passes through`() {
        val captured = CapturedSearch()
        toolWithHits(sampleHits, captured = captured).execute("""{"query":"hello world"}""")
        assertEquals("hello world", captured.query)
        assertEquals(5, captured.limit)
    }

    @Test
    fun `max_results is coerced to 1 and 10 and passed to search`() {
        val captured = CapturedSearch()
        val tool = toolWithHits(sampleHits, captured = captured)
        tool.execute("""{"query":"q","max_results":0}""")
        assertEquals(1, captured.limit)
        tool.execute("""{"query":"q","max_results":-3}""")
        assertEquals(1, captured.limit)
        tool.execute("""{"query":"q","max_results":99}""")
        assertEquals(10, captured.limit)
        tool.execute("""{"query":"q","max_results":3}""")
        assertEquals(3, captured.limit)
    }

    @Test
    fun `results array is valid JSON and hits round-trip through it`() {
        val raw = toolWithHits(sampleHits).execute("""{"query":"q","max_results":2}""")
        val parsed = JSONObject(raw)
        val results = parsed.getJSONArray("results")
        // Every entry exposes exactly the contracted fields.
        for (i in 0 until results.length()) {
            val entry: JSONObject = results.getJSONObject(i)
            val keys = mutableListOf<String>()
            val keyIter = entry.keys()
            while (keyIter.hasNext()) keys.add(keyIter.next())
            assertEquals(setOf("source", "part", "text"), keys.toSet())
        }
        assertEquals(2, JSONArray(results.toString()).length())
    }
}
