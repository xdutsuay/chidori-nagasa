package com.druk.lmplayground.computer.desktop

import android.content.Context
import com.druk.lmplayground.computer.CosConstants

/**
 * Persistence for the desktop extras choices (flavour, Firefox opt-in), stored in the
 * shared COS prefs ([CosConstants.PREFS_NAME]). Keys and defaults are the pure
 * constants in [DesktopFlavors]; [LocalDesktopBridge] reads the flavour on every start.
 */
object DesktopExtrasPrefs {

    private fun prefs(context: Context) =
        context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE)

    /** Persisted flavour, or [DesktopFlavor.OPENBOX] when unset / unknown. */
    fun flavor(context: Context): DesktopFlavor =
        DesktopFlavor.fromId(prefs(context).getString(DesktopFlavors.PREFS_KEY_DESKTOP_FLAVOR, null))

    fun setFlavor(context: Context, flavor: DesktopFlavor) {
        prefs(context).edit().putString(DesktopFlavors.PREFS_KEY_DESKTOP_FLAVOR, flavor.id).apply()
    }

    /** Last Firefox add-on choice (default false — it is a large opt-in download). */
    fun firefox(context: Context): Boolean =
        prefs(context).getBoolean(DesktopFlavors.PREFS_KEY_FIREFOX, false)

    fun setFirefox(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(DesktopFlavors.PREFS_KEY_FIREFOX, value).apply()
    }
}
