package com.druk.lmplayground.computer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Evidence for CP1 without a device: dumps the tools JSON a fresh chat would send
 * (Computer enabled, no stored tool prefs) and the composed system prompt for each
 * COS state. Output dir: env CP1_EVIDENCE_DIR, else build/cp1-evidence.
 */
@RunWith(RobolectricTestRunner::class)
class Cp1EvidenceDumpTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        CosFiles.deleteTree(File(context.filesDir, CosConstants.COS_DIR))
    }

    private fun outDir(): File =
        File(System.getenv("CP1_EVIDENCE_DIR") ?: "build/cp1-evidence").apply { mkdirs() }

    @Test
    fun `dump fresh chat tools json and system prompts`() = runTest {
        context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val cprefs = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        cprefs.edit().clear().commit()
        ComputerPrefs.setComputerEnabled(cprefs, true)

        // Same hydration as ConversationViewModel / GenerationCoordinator.
        val storage = StoragePreferences(context)
        val registry = ToolRegistry.createDefault(context)
        for (tool in registry.getAllTools()) {
            registry.setToolEnabled(tool.name, storage.effectiveToolEnabled("fresh-chat.gguf", tool.name))
        }
        val toolsJson = registry.toOpenAIToolsJson()
        val names = JSONArray(toolsJson).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertEquals(listOf("cos_exec"), names)

        val userPrompt = "You are a helpful assistant."
        // NOT_SET_UP: Computer enabled, no rootfs yet (live state, same as ModelRuntime).
        val notSetUp = CosPromptNote.sessionSystemPrompt(userPrompt, true, CosPromptNote.stateFor(context))
        assertTrue(notSetUp.contains("Computer screen"))
        // READY: install a (fake-verified) rootfs, then read live state again.
        val rootfs = File(context.filesDir, "cos/rootfs")
        val tarball = File(context.cacheDir, "fake.tar.gz").apply { writeText("t") }
        val ok = CosProvisioning.installVerified(
            tarball, CosProvisioning.sha256Hex(tarball), rootfs, CosConstants.ALPINE_VERSION, "x86_64",
        ) { _, dest -> File(dest, "bin").mkdirs(); CosProvisioning.Outcome(true, "ok") }
        assertTrue(ok.message, ok.ok)
        val liveAfterInstall = CosPromptNote.stateFor(context)
        val readyState = CosPromptNote.stateOf(true, true)
        val ready = CosPromptNote.sessionSystemPrompt(userPrompt, true, readyState)
        assertTrue(ready.contains("cos_exec"))
        val nodeMode = CosPromptNote.sessionSystemPrompt(userPrompt, false, readyState)
        assertEquals(userPrompt, nodeMode)
        ComputerPrefs.setComputerEnabled(cprefs, false)
        val disabled = CosPromptNote.sessionSystemPrompt(userPrompt, true, CosPromptNote.stateFor(context))
        assertEquals(userPrompt, disabled)

        val dir = outDir()
        File(dir, "fresh-chat-tools.json").writeText(JSONArray(toolsJson).toString(2) + "\n")
        File(dir, "system-prompt-ready.txt").writeText(ready)
        File(dir, "system-prompt-not-set-up.txt").writeText(notSetUp)
        File(dir, "system-prompt-disabled.txt").writeText(disabled)
        File(dir, "README.txt").writeText(
            "CP1 evidence (JVM/Robolectric; no device). Computer enabled, no stored tool prefs.\n" +
                "fresh-chat-tools.json = ToolRegistry.toOpenAIToolsJson() after the app's hydration loop.\n" +
                "system-prompt-*.txt = CosPromptNote.sessionSystemPrompt(\"$userPrompt\", chat=true, state).\n" +
                "live stateFor() after a verified install under Robolectric: $liveAfterInstall " +
                "(Robolectric has no real nativeLibraryDir/ABI).\n",
        )
    }
}
