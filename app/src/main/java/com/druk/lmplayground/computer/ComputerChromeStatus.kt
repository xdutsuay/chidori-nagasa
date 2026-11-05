package com.druk.lmplayground.computer

import com.druk.lmplayground.computer.desktop.DesktopState

/**
 * CP6 — one coarse Computer status shared by the COS screen chrome, Desktop
 * tab, and chat note. Maps the richer [DesktopState] machine onto four user-
 * facing buckets (plus transitional install/start/stop folded in).
 */
enum class ComputerChromeStatus {
    NOT_INSTALLED,
    INSTALLING,
    RUNNING,
    STOPPED,
    ;

    companion object {
        fun from(state: DesktopState): ComputerChromeStatus = when (state) {
            DesktopState.NotInstalled -> NOT_INSTALLED
            is DesktopState.Installing -> INSTALLING
            DesktopState.Starting, is DesktopState.Running -> RUNNING
            DesktopState.Stopping, DesktopState.Stopped, is DesktopState.Error -> STOPPED
        }

        /** Stable English label for tests / chat system note lines. */
        fun label(status: ComputerChromeStatus): String = when (status) {
            NOT_INSTALLED -> "not installed"
            INSTALLING -> "installing"
            RUNNING -> "running"
            STOPPED -> "stopped"
        }
    }
}
