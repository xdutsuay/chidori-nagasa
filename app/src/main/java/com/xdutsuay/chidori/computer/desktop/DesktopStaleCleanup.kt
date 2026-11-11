package com.xdutsuay.chidori.computer.desktop

import java.io.File

/**
 * Host-side removal of leftover Xvfb lock/socket files under the COS rootfs after a
 * force-kill (or crashed session) so the next [DesktopSession.start] is not blocked
 * by a stale `/tmp/.X<N>-lock`. Guest launch script also does this; this is the
 * Android-side backstop when the guest never runs.
 */
object DesktopStaleCleanup {

    fun removeXLocks(hostRootfs: File, display: Int) {
        val n = display.coerceAtLeast(0)
        val tmp = File(hostRootfs, "tmp")
        File(tmp, ".X$n-lock").delete()
        File(File(tmp, ".X11-unix"), "X$n").delete()
    }
}
