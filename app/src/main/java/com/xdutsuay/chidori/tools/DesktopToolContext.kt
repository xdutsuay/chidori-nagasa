package com.xdutsuay.chidori.tools

import android.content.Context
import com.xdutsuay.chidori.computer.ComputerPrefs
import com.xdutsuay.chidori.computer.CosConstants
import com.xdutsuay.chidori.computer.desktop.DesktopBridge
import com.xdutsuay.chidori.computer.desktop.DesktopBridges
import com.xdutsuay.chidori.computer.desktop.DesktopState

/**
 * What `cos_screenshot` and `cos_gui` share: the Computer toggle, the desktop
 * bridge (looked up on every call, so a bridge published after the chat opened
 * is picked up), and the scale of the last screenshot sent to the model (so
 * `cos_gui` can accept coordinates in screenshot pixels).
 */
class DesktopToolContext(
    val computerEnabled: () -> Boolean,
    val bridgeProvider: () -> DesktopBridge?,
) {
    /** screen px per image px of the last cos_screenshot in this chat, or null. */
    @Volatile var lastScreenshotScale: Double? = null

    fun isComputerEnabled(): Boolean = runCatching { computerEnabled() }.getOrDefault(false)

    fun bridge(): DesktopBridge? = runCatching { bridgeProvider() }.getOrNull()

    /** The Running state, or null when Computer is off or the desktop is not running. */
    fun running(): DesktopState.Running? {
        if (!isComputerEnabled()) return null
        val b = bridge() ?: return null
        val running = runCatching { b.isDesktopRunning() }.getOrDefault(false)
        if (!running) return null
        return b.state.value as? DesktopState.Running
    }

    /** Tools are advertised (and in the COS note) only while this is true. */
    fun isDesktopAvailable(): Boolean = running() != null

    companion object {
        fun forApp(context: Context): DesktopToolContext {
            val app = context.applicationContext
            return DesktopToolContext(
                computerEnabled = {
                    ComputerPrefs.isComputerEnabled(
                        app.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE),
                    )
                },
                bridgeProvider = { DesktopBridges.bridge },
            )
        }
    }
}
