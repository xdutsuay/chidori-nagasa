package com.druk.lmplayground.computer.desktop

/**
 * Pure transition function over the shared [DesktopState] (defined in DesktopBridge.kt).
 * [LocalDesktopBridge] and [ScriptedDesktopBridge] move only through [next], so the allowed
 * transitions are unit tested in one place:
 *
 * ```
 * NotInstalled ─InstallStarted→ Installing ─InstallSucceeded→ Stopped
 *                                  └─InstallFailed→ Error
 * Stopped/Error ─StartRequested→ Starting ─Ready→ Running
 *                                   └─StartFailed→ Error
 * Starting/Running ─StopRequested→ Stopping ─Stopped→ Stopped
 * Starting/Running ─Exited(msg)→ Error                (session died on its own)
 * Stopped/Error ─InstallStarted→ Installing           (reinstall / add Firefox)
 * idle ─Uninstalled→ NotInstalled                     (rootfs reinstalled or pins changed)
 * ```
 * Whether Start is offered from Error depends on the install marker, which the bridge
 * checks; the machine itself only rejects starts from busy states.
 * Returns null for a transition that is not allowed (callers ignore the event).
 */
object DesktopStateMachine {

    sealed interface Event {
        data object InstallStarted : Event
        data class InstallProgress(val progress: Float?) : Event
        data object InstallSucceeded : Event
        data class InstallFailed(val message: String, val cause: Throwable? = null) : Event
        data object StartRequested : Event
        data class Ready(val running: DesktopState.Running) : Event
        data class StartFailed(val message: String, val cause: Throwable? = null) : Event
        data object StopRequested : Event
        data object Stopped : Event
        data class Exited(val message: String) : Event
        data object Uninstalled : Event
    }

    private fun isIdle(s: DesktopState) =
        s == DesktopState.NotInstalled || s == DesktopState.Stopped || s is DesktopState.Error

    fun next(state: DesktopState, event: Event): DesktopState? = when (event) {
        Event.InstallStarted -> if (isIdle(state)) DesktopState.Installing(null) else null
        is Event.InstallProgress -> if (state is DesktopState.Installing) {
            DesktopState.Installing(event.progress?.coerceIn(0f, 1f) ?: state.progress)
        } else {
            null
        }
        Event.InstallSucceeded -> if (state is DesktopState.Installing) DesktopState.Stopped else null
        is Event.InstallFailed -> if (state is DesktopState.Installing) DesktopState.Error(event.message, event.cause) else null
        Event.StartRequested -> if (state == DesktopState.Stopped || state is DesktopState.Error) DesktopState.Starting else null
        is Event.Ready -> if (state == DesktopState.Starting) event.running else null
        is Event.StartFailed -> if (state == DesktopState.Starting) DesktopState.Error(event.message, event.cause) else null
        Event.StopRequested -> if (state == DesktopState.Starting || state is DesktopState.Running) DesktopState.Stopping else null
        Event.Stopped -> when (state) {
            DesktopState.Stopping, DesktopState.Starting, is DesktopState.Running -> DesktopState.Stopped
            else -> null
        }
        is Event.Exited -> when (state) {
            is DesktopState.Running, DesktopState.Starting -> DesktopState.Error(event.message, null)
            else -> null
        }
        Event.Uninstalled -> if (isIdle(state)) DesktopState.NotInstalled else null
    }

    fun canStop(state: DesktopState): Boolean = state == DesktopState.Starting || state is DesktopState.Running

    fun canInstall(state: DesktopState): Boolean = isIdle(state)

    /** One-line status for the Computer screen. */
    fun label(state: DesktopState, installMessage: String? = null): String = when (state) {
        DesktopState.NotInstalled -> "Not installed"
        is DesktopState.Installing ->
            "Installing" + (state.progress?.let { " ${(it * 100).toInt()}%" } ?: "") +
                (installMessage?.let { " · $it" } ?: "")
        DesktopState.Stopped -> "Stopped"
        DesktopState.Starting -> "Starting…"
        is DesktopState.Running -> "Running ${state.display} · ${state.width}×${state.height}×${state.depth}"
        DesktopState.Stopping -> "Stopping…"
        is DesktopState.Error -> "Error: ${state.message}"
    }
}
