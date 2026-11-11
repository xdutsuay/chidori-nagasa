package com.xdutsuay.chidori.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xdutsuay.chidori.computer.ComputerPrefs
import com.xdutsuay.chidori.computer.CosConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoragePreferencesCosDefaultTest {
    private lateinit var context: Context
    private lateinit var prefs: StoragePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        computerPrefs().edit().clear().commit()
        prefs = StoragePreferences(context)
    }

    private fun computerPrefs() = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
    private fun enableComputer(on: Boolean) = ComputerPrefs.setComputerEnabled(computerPrefs(), on)

    @Test
    fun `cos_exec on by default for a fresh or existing chat once Computer is enabled`() {
        enableComputer(true)
        // Existing chat with web tools configured but nothing stored for cos_exec.
        prefs.setToolOverride("existing.gguf", "web_search", true)
        assertTrue(prefs.effectiveToolEnabled("existing.gguf", "cos_exec"))
        assertTrue(prefs.effectiveToolEnabled("fresh.gguf", "cos_exec"))
        assertTrue(prefs.isToolEnabledDefault("cos_exec"))
    }

    @Test
    fun `cos_exec not offered while Computer is disabled`() {
        enableComputer(false)
        assertFalse(prefs.effectiveToolEnabled("fresh.gguf", "cos_exec"))
        prefs.setToolOverride("fresh.gguf", "cos_exec", true)
        assertFalse(prefs.effectiveToolEnabled("fresh.gguf", "cos_exec"))
    }

    @Test
    fun `global explicit off is respected`() {
        enableComputer(true)
        prefs.setToolEnabledDefault("cos_exec", false)
        assertFalse(prefs.effectiveToolEnabled("fresh.gguf", "cos_exec"))
        assertFalse(prefs.isToolEnabledDefault("cos_exec"))
    }

    @Test
    fun `per chat model toggle off is respected`() {
        enableComputer(true)
        prefs.setToolOverride("m.gguf", "cos_exec", false)
        assertFalse(prefs.effectiveToolEnabled("m.gguf", "cos_exec"))
        assertTrue(prefs.effectiveToolEnabled("other.gguf", "cos_exec"))
    }

    @Test
    fun `other tools stay off by default`() {
        enableComputer(true)
        assertFalse(prefs.effectiveToolEnabled("fresh.gguf", "web_search"))
        assertFalse(prefs.isToolEnabledDefault("web_fetch"))
    }
}
