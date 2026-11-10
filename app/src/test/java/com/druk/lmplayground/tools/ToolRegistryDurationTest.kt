package com.druk.lmplayground.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Per-call durations in [ToolRegistry.executeToolCalls] results.
 *
 * Each result entry must carry `duration_ms` = (clock after the call's content
 * was produced) - (clock before it), with the fake clock read exactly twice per
 * call, in call order. Unknown / disabled / batch-cancelled calls still get the
 * key, and the `content` string itself must stay untouched (no duration inside).
 */
@RunWith(RobolectricTestRunner::class)
class ToolRegistryDurationTest {

    /** Fake monotonic clock; tools advance it to simulate real work. */
    private var clockMs = 0L

    /** Counts every read of the clock so we can assert exactly-two-reads-per-call. */
    private var clockReads = 0

    private fun fakeClock(): Long {
        clockReads++
        return clockMs
    }

    private inner class SlowTool(override val name: String, private val costMs: Long) : Tool {
        override val description = "fake $name"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override fun execute(arguments: String): String {
            clockMs += costMs
            return """{"ok":"$name ran"}"""
        }
    }

    private fun call(id: String, name: String, args: String) =
        JSONObject().put("id", id).put("name", name).put("arguments", args)

    private fun entry(results: String, id: String): JSONObject {
        val arr = JSONArray(results)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) return o
        }
        error("no result for $id in $results")
    }

    private fun registryWith(vararg tools: Tool, clock: () -> Long = { fakeClock() }): ToolRegistry =
        ToolRegistry(nowMs = clock).apply {
            tools.forEach { register(it); setToolEnabled(it.name, true) }
        }

    @Test
    fun `per-call durations come from the clock around each execute`() {
        clockMs = 1000L
        val registry = registryWith(SlowTool("fast_tool", 5), SlowTool("slow_tool", 4000))
        val results = registry.executeToolCalls(
            JSONArray().put(call("1", "fast_tool", "{}")).put(call("2", "slow_tool", "{}")).toString(),
        )
        // fast: before=1000, execute advances to 1005 -> duration 5
        assertEquals(5L, entry(results, "1").getLong("duration_ms"))
        // slow: before=1005, execute advances to 5005 -> duration 4000
        assertEquals(4000L, entry(results, "2").getLong("duration_ms"))
    }

    @Test
    fun `durations reflect call order not a shared even split`() {
        clockMs = 0L
        val registry = registryWith(SlowTool("slow_tool", 4000), SlowTool("fast_tool", 5))
        val results = registry.executeToolCalls(
            JSONArray().put(call("1", "slow_tool", "{}")).put(call("2", "fast_tool", "{}")).toString(),
        )
        assertEquals(4000L, entry(results, "1").getLong("duration_ms"))
        assertEquals(5L, entry(results, "2").getLong("duration_ms"))
    }

    @Test
    fun `clock is read exactly twice per call`() {
        clockMs = 0L
        val registry = registryWith(SlowTool("fast_tool", 5), SlowTool("slow_tool", 4000))
        registry.executeToolCalls(
            JSONArray().put(call("1", "fast_tool", "{}")).put(call("2", "slow_tool", "{}")).toString(),
        )
        assertEquals(4, clockReads)
    }

    @Test
    fun `duration_ms is a non-negative long on every entry`() {
        clockMs = 0L
        val registry = registryWith(SlowTool("a", 3), SlowTool("b", 7))
        val results = registry.executeToolCalls(
            JSONArray().put(call("1", "a", "{}")).put(call("2", "b", "{}")).toString(),
        )
        val arr = JSONArray(results)
        for (i in 0 until arr.length()) {
            val duration = arr.getJSONObject(i).getLong("duration_ms")
            assertTrue("duration_ms must be >= 0, was $duration", duration >= 0)
        }
    }

    @Test
    fun `content is unchanged and does not carry the duration`() {
        clockMs = 0L
        val registry = registryWith(SlowTool("fast_tool", 5))
        val results = registry.executeToolCalls(JSONArray().put(call("1", "fast_tool", "{}")).toString())
        val e = entry(results, "1")
        assertEquals("fast_tool", e.getString("name"))
        val content = JSONObject(e.getString("content"))
        assertFalse("duration must not appear inside content", content.has("duration_ms"))
        assertFalse(e.getString("content").contains("duration_ms"))
        assertEquals("fast_tool ran", content.getString("ok"))
    }

    @Test
    fun `unknown tool entry still gets a duration_ms`() {
        clockMs = 777L
        val results = ToolRegistry(nowMs = ::fakeClock)
            .executeToolCalls(JSONArray().put(call("1", "nope", "{}")).toString())
        // Frozen clock: before == after == 777 -> 0, but the key must exist.
        assertEquals(0L, entry(results, "1").getLong("duration_ms"))
    }

    @Test
    fun `disabled tool entry still gets a duration_ms`() {
        clockMs = 0L
        val cos = SlowTool(CosToolDefaults.COS_EXEC, 100)
        val registry = registryWith(cos).apply { setToolEnabled(CosToolDefaults.COS_EXEC, false) }
        val results = registry.executeToolCalls(JSONArray().put(call("1", CosToolDefaults.COS_EXEC, "{}")).toString())
        assertEquals(0L, entry(results, "1").getLong("duration_ms"))
        assertEquals("frozen clock read once before, once after the skipped call", 2, clockReads)
    }

    @Test
    fun `batch-cancelled call still gets a duration_ms`() {
        clockMs = 0L
        lateinit var registry: ToolRegistry
        val first = object : Tool {
            override val name = "web_search"
            override val description = "x"
            override val parametersSchema = "{}"
            override fun execute(arguments: String): String {
                clockMs += 10
                registry.cancelInFlight()
                return "{}"
            }
        }
        registry = registryWith(first, SlowTool("web_fetch", 50))
        val results = registry.executeToolCalls(
            JSONArray().put(call("1", "web_search", "{}")).put(call("2", "web_fetch", "{}")).toString(),
        )
        assertEquals(10L, entry(results, "1").getLong("duration_ms"))
        // Cancelled call never ran; frozen clock before/after -> 0, key still present.
        assertEquals(0L, entry(results, "2").getLong("duration_ms"))
    }

    @Test
    fun `default constructor keeps compiling and still emits duration_ms`() {
        clockMs = 0L
        val registry = ToolRegistry().apply { register(SlowTool("fast_tool", 5)); setToolEnabled("fast_tool", true) }
        val results = registry.executeToolCalls(JSONArray().put(call("1", "fast_tool", "{}")).toString())
        assertTrue(entry(results, "1").getLong("duration_ms") >= 0)
    }
}
