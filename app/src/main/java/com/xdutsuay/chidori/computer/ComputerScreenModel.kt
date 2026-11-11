package com.xdutsuay.chidori.computer

/**
 * Pure presentation logic for [ComputerScreen]: the one-line status and the
 * overflow-menu actions (with their enabled state). Kept out of the composable so
 * it is unit-testable on the JVM.
 */
object ComputerScreenModel {

    /** Every action that lives in the top-bar ⋮ menu (no button rows on screen). */
    enum class Action { INSTALL_ROOTFS, REINSTALL_ROOTFS, INSTALL_DESKTOP, SMOKE, PRUNE, NEW_SESSION, CLEAR_TERMINAL }

    data class MenuItem(val action: Action, val enabled: Boolean)

    data class RecipeItem(val id: String, val title: String, val installed: Boolean, val enabled: Boolean)

    /**
     * @param desktopIdle the desktop is not installing / starting / running (from
     *   `DesktopStateMachine.canInstall`); "Install desktop" also needs the rootfs.
     */
    fun menuItems(state: ComputerUiState, desktopIdle: Boolean = true): List<MenuItem> {
        val idle = state.enabled && !state.busy
        val rootfsReady = state.status == ComputerStatus.Ready || state.status == ComputerStatus.Running
        return listOf(
            MenuItem(Action.INSTALL_ROOTFS, idle),
            MenuItem(Action.REINSTALL_ROOTFS, idle),
            MenuItem(Action.INSTALL_DESKTOP, idle && rootfsReady && desktopIdle),
            MenuItem(Action.SMOKE, idle),
            MenuItem(Action.PRUNE, state.enabled),
            MenuItem(Action.NEW_SESSION, true),
            MenuItem(Action.CLEAR_TERMINAL, true),
        )
    }

    fun recipeItems(state: ComputerUiState): List<RecipeItem> = state.recipes.map { r ->
        val installed = r.id in state.installedRecipes
        RecipeItem(r.id, r.title, installed, state.enabled && !installed)
    }

    fun statusLabel(status: ComputerStatus): String = when (status) {
        ComputerStatus.Disabled -> "Off"
        ComputerStatus.MissingRootfs -> "Not set up"
        ComputerStatus.Ready -> "Ready"
        ComputerStatus.Running -> "Running"
        is ComputerStatus.Error -> "Error: ${status.message}"
    }

    /** Runtime mode label: `proot` or `proot·no-seccomp`; `—` while off. */
    fun runtimeLabel(state: ComputerUiState): String = when {
        !state.enabled -> "—"
        state.prootNoSeccomp -> "proot·no-seccomp"
        else -> "proot"
    }

    /**
     * Compact single line, e.g. `Ready · alpine · COS storage 3.1 MB · /`.
     * Runtime mode and session id stay out of it (see [runtimeLabel]).
     * Never contains a newline (status messages are flattened); the UI ellipsizes it.
     */
    fun statusLine(state: ComputerUiState): String {
        val parts = buildList {
            add(statusLabel(state.status))
            add(CosRootfsInfo.statusLine(state.rootfsMode, state.rootfsUsedBytes))
            add(CosCwd.promptLabel(state.cwd).removePrefix("cos:").removeSuffix(" $"))
        }
        return parts.joinToString(" · ").replace(Regex("\\s*[\\r\\n]+\\s*"), " ")
    }

    /** Last non-blank terminal line, for the collapsed terminal row. */
    fun lastTerminalLine(terminal: String): String =
        terminal.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""

    /** True when the input row can submit (Enter / send icon). */
    fun canRun(state: ComputerUiState): Boolean =
        state.enabled && !state.busy && state.input.isNotBlank()
}
