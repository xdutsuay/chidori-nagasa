package com.xdutsuay.chidori.computer.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The VNC viewer phase-1 package pin: x11vnc at the Alpine version recorded when the
 * pin was added. [DesktopPackages.VIEWER_PINS] feeds the `apk add name=version` fallback
 * in [com.xdutsuay.chidori.computer.desktop.share.VncShare].
 */
class DesktopViewerPinsTest {

    @Test
    fun `x11vnc is pinned to the recorded Alpine version`() {
        assertEquals(mapOf("x11vnc" to "0.9.17-r1"), DesktopPackages.VIEWER_PINS)
    }

    @Test
    fun `viewer pin set is exactly x11vnc`() {
        assertEquals(setOf("x11vnc"), DesktopPackages.VIEWER_PINS.keys)
    }
}
