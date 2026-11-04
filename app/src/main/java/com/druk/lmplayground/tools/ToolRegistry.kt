package com.druk.lmplayground.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry {
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

    fun hasEnabledTools(): Boolean = tools.keys.any { isToolEnabled(it) }

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
     * Returns OpenAI-format JSON for enabled tools only.
     */
    fun toOpenAIToolsJson(): String {
        val arr = JSONArray()
        for (tool in tools.values) {
            if (!isToolEnabled(tool.name)) continue
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
            // Enforce the per-chat toggle at execution time, not only in the advertised
            // schema: a model can still emit a call to a tool it saw earlier (e.g.
            // cos_exec after the user switched it off) while other tools keep the
            // tool-call parser active. Disabled tools never run.
            val content = when {
                tool == null -> """{"error":"Unknown tool: $name"}"""
                !isToolEnabled(name) -> disabledError(name)
                batchCancelled -> CANCELLED_ERROR
                else -> tool.execute(arguments)
            }

            val result = JSONObject()
            result.put("id", id)
            result.put("name", name)
            result.put("content", content)
            results.put(result)
        }
        return results.toString()
    }

    companion object {
        /** Result for calls skipped because Stop was pressed earlier in the batch. */
        val CANCELLED_ERROR: String = """{"error":"cancelled: the user pressed Stop; not run"}"""

        /** Tool result returned for a call to a tool that is switched off. */
        fun disabledError(name: String): String =
            JSONObject().put("error", "$name is disabled in this chat; it was not run. Do not call it.").toString()

        fun createDefault(context: Context): ToolRegistry {
            return ToolRegistry().apply {
                // Shared link store: web_search hands the model compact
                // "ddg:N" references instead of long URLs, web_fetch resolves
                // them back at call time. See WebLinkStore.
                register(WebSearchTool(webLinkStore))
                register(WebFetchTool(webLinkStore))
                register(JavaScriptTool(context))
                register(CosExecTool(context))
            }
        }
    }
}
