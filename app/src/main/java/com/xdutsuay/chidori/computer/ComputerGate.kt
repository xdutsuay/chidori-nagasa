package com.xdutsuay.chidori.computer

/** Pure gating policy mapping (enabled, rootfsPresent, running) to status. */
object ComputerGate {
    fun resolveStatus(
        enabled: Boolean,
        rootfsPresent: Boolean,
        running: Boolean,
    ): ComputerStatus = when {
        !enabled -> ComputerStatus.Disabled
        !rootfsPresent -> ComputerStatus.MissingRootfs
        running -> ComputerStatus.Running
        else -> ComputerStatus.Ready
    }
}
