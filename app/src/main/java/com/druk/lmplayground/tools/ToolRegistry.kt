package com.druk.lmplayground.tools

import com.druk.lmplayground.library.Library
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val tools = mutableMapOf<String, Tool>()
    private val enabledState = mutableMapOf<String, Boolean>()

    /**
     * Shared by web_search / web_fetch for compact result references. Exposed so
     * the conversation layer can snapshot it into a session's metadata and
     * restore it when that conversation is reopened. See [WebLinkStore].
     */
    val webLinkStore = WebLinkStore()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun getAllTools(): List<Tool> = tools.values.toList()

    fun getTool(name: String): Tool? = tools[name]

    fun isToolEnabled(name: String): Boolean = enabledState[name] ?: false

    fun setToolEnabled(name: String, enabled: Boolean) {
        enabledState[name] = enabled
    }

    fun hasEnabledTools(): Boolean = tools.values.any { isToolActive(it) }

    /** Enabled by the user AND currently available (see [Tool.isAvailable]). */
    fun isToolActive(name: String): Boolean = tools[name]?.let { isToolActive(it) } ?: false

    private fun isToolActive(tool: Tool): Boolean =
        isToolEnabled(tool.name) && runCatching { tool.isAvailable() }.getOrDefault(false)

    /**
     * Images produced by tools in the current round (`cos_screenshot`), attached to
     * the tool results by the generation loop. See [ToolImageAttachments].
     */
    val toolImages = ToolImageAttachments()

    @Volatile private var batchCancelled = false

    /**
     * Abort in-flight work across all tools (e.g. on Stop during a tool call) and
     * skip any calls of the current [executeToolCalls] batch that haven't started.
     */
    fun cancelInFlight() {
        batchCancelled = true
        for (tool in tools.values) {
            try { tool.cancelInFlight() } catch (_: Throwable) {}
        }
    }

    /**
     * Returns OpenAI-format JSON for enabled, currently available tools only.
     */
    fun toOpenAIToolsJson(): String {
        val arr = JSONArray()
        for (tool in tools.values) {
            if (!isToolActive(tool)) continue
            val obj = JSONObject()
            obj.put("type", "function")
            val func = JSONObject()
            func.put("name", tool.name)
            func.put("description", tool.description)
            func.put("parameters", JSONObject(tool.parametersSchema))
            obj.put("function", func)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun executeToolCalls(toolCallsJson: String): String {
        batchCancelled = false
        val calls = JSONArray(toolCallsJson)
        val results = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val name = call.getString("name")
            val arguments = call.getString("arguments")
            val id = call.getString("id")

            val tool = getTool(name)
            val started = nowMs()
            // Enforce the per-chat toggle at execution time, not only in the advertised
            // schema: a model can still emit a call to a tool it saw earlier (e.g.
            // cos_exec after the user switched it off) while other tools keep the
            // tool-call parser active. Disabled tools never run.
            val content = when {
                tool == null -> """{"error":"Unknown tool: $name"}"""
                !isToolEnabled(name) -> disabledError(name)
                batchCancelled -> CANCELLED_ERROR
                // Availability (e.g. the COS desktop stopped since the schema was
                // sent) is re-checked by the tool itself: the desktop tools answer
                // with cos_exec's disabled shape or a "desktop not running" error.
                else -> tool.execute(arguments)
            }
            val durationMs = (nowMs() - started).coerceAtLeast(0)

            val result = JSONObject()
            result.put("id", id)
            result.put("name", name)
            result.put("content", content)
            result.put(DURATION_MS, durationMs)
            results.put(result)
        }
        return results.toString()
    }

    companion object {
        /** Per-call wall time on each result entry (not part of the content the model sees). */
        const val DURATION_MS = "duration_ms"

        /** Result for calls skipped because Stop was pressed earlier in the batch. */
        val CANCELLED_ERROR: String = """{"error":"cancelled: the user pressed Stop; not run"}"""

        /** Tool result returned for a call to a tool that is switched off. */
        fun disabledError(name: String): String =
            JSONObject().put("error", "$name is disabled in this chat; it was not run. Do not call it.").toString()

        fun createDefault(context: Context): ToolRegistry {
            val desktop = DesktopToolContext.forApp(context)
            return ToolRegistry().apply {
                // Shared link store: web_search hands the model compact
                // "ddg:N" references instead of long URLs, web_fetch resolves
                // them back at call time. See WebLinkStore.
                register(WebSearchTool(webLinkStore))
                register(WebFetchTool(webLinkStore))
                register(JavaScriptTool(context))
                register(Library.searchTool(context))
                register(CosExecTool(context))
                // CP2: COS desktop tools, advertised only while the desktop runs.
                register(CosScreenshotTool(desktop, toolImages))
                register(CosGuiTool(context, desktop))
            }
        }
    }
}
