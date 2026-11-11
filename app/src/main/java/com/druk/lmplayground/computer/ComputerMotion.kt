package com.druk.lmplayground.computer

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Quiet motion for the Computer screen: short, calm durations and a reduced-motion switch. */
object ComputerMotion {
    /** Cross-fade between the Desktop / Terminal / Activity tabs. */
    const val TAB_TRANSITION_MS = 220

    /** Size changes (status pill label, desktop panel content). */
    const val RESIZE_MS = 180

    /** One full in-and-out breath of the Running dot. */
    const val PULSE_PERIOD_MS = 1600

    /** Lowest alpha the Running dot fades to mid-pulse. */
    const val PULSE_MIN_ALPHA = 0.35f

    /** Value of `Settings.Global.ANIMATOR_DURATION_SCALE` when the user has not changed it. */
    const val DEFAULT_ANIMATOR_DURATION_SCALE = 1f

    /**
     * Whether the Computer screen may animate, given the system animator duration scale
     * (`Settings.Global.ANIMATOR_DURATION_SCALE`, "Remove animations" sets it to 0).
     *
     * Returns `true` only when [durationScale] is a finite number strictly greater than 0.
     * Returns `false` for 0 (animations removed), negative values, NaN and infinities,
     * so a corrupt setting fails quiet (no motion) rather than loud.
     */
    fun animationsEnabled(durationScale: Float): Boolean = durationScale.isFinite() && durationScale > 0f
}

/** Reads the system animator duration scale once per composition of the caller. */
@Composable
fun rememberComputerAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        val scale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            ComputerMotion.DEFAULT_ANIMATOR_DURATION_SCALE,
        )
        ComputerMotion.animationsEnabled(scale)
    }
}
