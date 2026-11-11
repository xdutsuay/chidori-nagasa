package com.xdutsuay.chidori.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xdutsuay.chidori.computer.ComputerFlagStore
import com.xdutsuay.chidori.computer.CosConstants
import com.xdutsuay.chidori.computer.CosFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review D2: a disabled tool must not run even if the model emits a call to it
 * (the parser stays active while any other tool is on).
 */
@RunWith(RobolectricTestRunner::class)
class ToolRegistryEnforcementTest {
    private class CountingTool(override val name: String) : Tool {
        val calls = AtomicInteger()
        override val description = "fake $name"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override fun execute(arguments: String): String {
            calls.incrementAndGet()
            return """{"ok":"$name ran"}"""
        }
    }

    private fun call(id: String, name: String, args: String) =
        JSONObject().put("id", id).put("name", name).put("arguments", args)

    private fun contentOf(results: String, id: String): String {
        val arr = JSONArray(results)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) return o.getString("content")
        }
        error("no result for $id in $results")
    }

    @Test
    fun `disabled cos_exec call is rejected and never reaches the runtime`() {
        val cos = CountingTool(CosToolDefaults.COS_EXEC) // stands in for the COS runtime
        val web = CountingTool("web_search")
        val registry = ToolRegistry().apply {
            register(cos); register(web)
            setToolEnabled("web_search", true)
            setToolEnabled(CosToolDefaults.COS_EXEC, false)
        }
        // Model only sees web_search, yet calls cos_exec (and web_search) anyway.
        assertFalse(registry.toOpenAIToolsJson().contains(CosToolDefaults.COS_EXEC))
        val calls = JSONArray()
            .put(call("1", CosToolDefaults.COS_EXEC, """{"command":"rm -rf /root"}"""))
            .put(call("2", "web_search", """{"query":"x"}"""))
        val results = registry.executeToolCalls(calls.toString())

        assertEquals(0, cos.calls.get())
        val err = JSONObject(contentOf(results, "1")).getString("error")
        assertTrue(err, err.startsWith("cos_exec is disabled"))
        assertEquals(1, web.calls.get())
        assertTrue(contentOf(results, "2").contains("web_search ran"))

        // Re-enabled -> runs again.
        registry.setToolEnabled(CosToolDefaults.COS_EXEC, true)
        registry.executeToolCalls(JSONArray().put(call("3", CosToolDefaults.COS_EXEC, "{}")).toString())
        assertEquals(1, cos.calls.get())
    }

    @Test
    fun `real CosExecTool disabled with Computer on does not touch COS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ComputerFlagStore.fromSharedPreferences(prefs).setComputerEnabled(true)
        val cosDir = File(context.filesDir, CosConstants.COS_DIR)
        CosFiles.deleteTree(cosDir)

        val registry = ToolRegistry().apply {
            register(CosExecTool(context))
            setToolEnabled(CosToolDefaults.COS_EXEC, false)
        }
        val results = registry.executeToolCalls(
            JSONArray().put(call("1", CosToolDefaults.COS_EXEC, """{"command":"uname -a"}""")).toString(),
        )
        assertTrue(JSONObject(contentOf(results, "1")).getString("error").startsWith("cos_exec is disabled"))
        // Had it run, CosAgentBridge would have started provisioning (rootfs download) under files/cos.
        assertFalse("COS was touched: ${cosDir.list()?.toList()}", cosDir.exists())
    }

    @Test
    fun `chat toggle on but Computer off resolves disabled and is rejected`() {
        // Review nit: the per-turn hydration (CosToolDefaults.resolve) feeds setToolEnabled.
        val enabled = CosToolDefaults.resolve(
            CosToolDefaults.COS_EXEC, override = true, globalDefault = true, computerEnabled = false,
        )
        assertFalse(enabled)
        val cos = CountingTool(CosToolDefaults.COS_EXEC)
        val registry = ToolRegistry().apply { register(cos); setToolEnabled(CosToolDefaults.COS_EXEC, enabled) }
        val results = registry.executeToolCalls(JSONArray().put(call("1", CosToolDefaults.COS_EXEC, "{}")).toString())
        assertTrue(JSONObject(contentOf(results, "1")).getString("error").startsWith("cos_exec is disabled"))
        assertEquals(0, cos.calls.get())
    }

    @Test
    fun `calls after Stop in the same batch are skipped`() {
        lateinit var registry: ToolRegistry
        val first = object : Tool {
            override val name = "web_search"
            override val description = "x"
            override val parametersSchema = "{}"
            override fun execute(arguments: String): String { registry.cancelInFlight(); return "{}" }
        }
        val second = CountingTool("web_fetch")
        registry = ToolRegistry().apply {
            register(first); register(second)
            setToolEnabled("web_search", true); setToolEnabled("web_fetch", true)
        }
        val results = registry.executeToolCalls(
            JSONArray().put(call("1", "web_search", "{}")).put(call("2", "web_fetch", "{}")).toString(),
        )
        assertEquals(0, second.calls.get())
        assertTrue(contentOf(results, "2").contains("cancelled"))
        // Next batch starts fresh.
        registry.executeToolCalls(JSONArray().put(call("3", "web_fetch", "{}")).toString())
        assertEquals(1, second.calls.get())
    }

    @Test
    fun `unknown tool still reports unknown`() {
        val results = ToolRegistry().executeToolCalls(JSONArray().put(call("1", "nope", "{}")).toString())
        assertTrue(contentOf(results, "1").contains("Unknown tool: nope"))
    }
}
