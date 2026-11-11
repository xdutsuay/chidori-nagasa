package com.xdutsuay.chidori.computer

/**
 * Battery / thermal gates for heavy COS work (ensureRootfs, long exec).
 * Pure functions — callers supply readings from Android PowerManager / BatteryManager.
 */
object ComputerResourceGates {
    /** Refuse heavy work below this battery percent (0–100). */
    const val MIN_BATTERY_PERCENT = 15

    /**
     * Android [android.os.PowerManager] thermal status constants (numeric):
     * NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6.
     * Block at SEVERE and above.
     */
    const val MAX_THERMAL_STATUS = 2 // MODERATE inclusive

    fun allowHeavyWork(batteryPercent: Int, thermalStatus: Int): Boolean {
        if (batteryPercent in 0 until MIN_BATTERY_PERCENT) return false
        if (thermalStatus > MAX_THERMAL_STATUS) return false
        return true
    }

    fun blockReason(batteryPercent: Int, thermalStatus: Int): String? = when {
        batteryPercent in 0 until MIN_BATTERY_PERCENT ->
            "Battery too low for COS work ($batteryPercent%)"
        thermalStatus > MAX_THERMAL_STATUS ->
            "Device too warm for COS work (thermal=$thermalStatus)"
        else -> null
    }
}
