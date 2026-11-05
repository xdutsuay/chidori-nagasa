package com.druk.lmplayground.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.computer.CosConstants
import com.druk.lmplayground.tools.CosConfirmPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CP7: [StoragePreferences] must expose the COS tool confirmation mode
 * ([CosConfirmPolicy.Mode], default [CosConfirmPolicy.Mode.AUTO] per
 * `ComputerPrefs.DEFAULT_CONFIRM_MODE`) and the evidence-dogfood toggle,
 * both persisted through [CosConstants.PREFS_NAME].
 */
@RunWith(RobolectricTestRunner::class)
class StoragePreferencesConfirmTest {
    private lateinit var context: Context
    private lateinit var prefs: StoragePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        computerPrefs().edit().clear().commit()
        prefs = StoragePreferences(context)
    }

    private fun computerPrefs() =
        context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `confirm mode defaults to AUTO`() {
        assertEquals(CosConfirmPolicy.Mode.AUTO, prefs.getCosConfirmMode())
    }

    @Test
    fun `confirm mode round-trips through ALWAYS`() {
        prefs.setCosConfirmMode(CosConfirmPolicy.Mode.ALWAYS)
        assertEquals(CosConfirmPolicy.Mode.ALWAYS, prefs.getCosConfirmMode())
    }

    @Test
    fun `confirm mode round-trips through ASK_ONCE`() {
        prefs.setCosConfirmMode(CosConfirmPolicy.Mode.ASK_ONCE)
        assertEquals(CosConfirmPolicy.Mode.ASK_ONCE, prefs.getCosConfirmMode())
    }

    @Test
    fun `confirm mode persists across StoragePreferences instances`() {
        prefs.setCosConfirmMode(CosConfirmPolicy.Mode.ALWAYS)
        val reloaded = StoragePreferences(context)
        assertEquals(CosConfirmPolicy.Mode.ALWAYS, reloaded.getCosConfirmMode())
    }

    @Test
    fun `evidence dogfood disabled by default`() {
        assertFalse(prefs.isEvidenceDogfoodEnabled())
    }

    @Test
    fun `evidence dogfood round-trips and persists across instances`() {
        prefs.setEvidenceDogfoodEnabled(true)
        assertTrue(StoragePreferences(context).isEvidenceDogfoodEnabled())
        prefs.setEvidenceDogfoodEnabled(false)
        assertFalse(StoragePreferences(context).isEvidenceDogfoodEnabled())
    }
}
