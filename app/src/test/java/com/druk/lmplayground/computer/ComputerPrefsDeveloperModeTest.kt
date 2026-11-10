package com.druk.lmplayground.computer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.computer.CosConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Developer mode gates the phase-1 VNC viewer UI: default OFF, round-trips through
 * SharedPreferences (same backing prefs as the other [ComputerPrefs] flags).
 */
@RunWith(RobolectricTestRunner::class)
class ComputerPrefsDeveloperModeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `developer mode is off by default`() {
        assertFalse(ComputerPrefs.isDeveloperMode(prefs()))
        assertEquals("computer.developer_mode", ComputerPrefs.KEY_DEVELOPER_MODE)
        assertFalse(ComputerPrefs.DEFAULT_DEVELOPER_MODE)
    }

    @Test
    fun `developer mode round-trips`() {
        ComputerPrefs.setDeveloperMode(prefs(), true)
        assertTrue(ComputerPrefs.isDeveloperMode(prefs()))
        ComputerPrefs.setDeveloperMode(prefs(), false)
        assertFalse(ComputerPrefs.isDeveloperMode(prefs()))
    }

    @Test
    fun `developer mode persists across reads`() {
        ComputerPrefs.setDeveloperMode(prefs(), true)
        val reloaded = context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(ComputerPrefs.isDeveloperMode(reloaded))
    }
}
