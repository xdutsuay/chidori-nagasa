package com.xdutsuay.chidori.computer.desktop.input

/**
 * How one-finger gestures on the COS viewer map to the X pointer.
 *
 * - [DIRECT]: touchscreen — tap/drag land at the finger's mapped desktop pixel.
 * - [TRACKPAD]: relative — drag moves the cursor (`mousemove_relative`); tap/long-press
 *   click at the *current* cursor (no jump).
 */
enum class DesktopPointerMode {
    DIRECT,
    TRACKPAD,
}
