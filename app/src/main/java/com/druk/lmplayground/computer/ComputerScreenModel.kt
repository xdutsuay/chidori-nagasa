package com.druk.lmplayground.computer

/**
 * Pure presentation logic for [ComputerScreen]: the one-line status and the
 * overflow-menu actions (with their enabled state). Kept out of the composable so
 * it is unit-testable on the JVM.
 */
object ComputerScreenModel {

    /** Every action that lives in the top-bar ⋮ menu (no button rows on screen). */
    enum class Action { INSTALL_ROOTFS, REINSTALL_ROOTFS, SMOKE, PRUNE, NEW_SESSION, CLEAR_TERMINAL }

    data class MenuItem(val action: Action, val enabled: Boolean)

    data class RecipeItem(val id: String, val title: String, val installed: Boolean, val enabled: Boolean)

    fun menuItems(state: ComputerUiState): List<MenuItem> {
        val idle = state.enabled && !state.busy
        return listOf(
            MenuItem(Action.INSTALL_ROOTFS, idle),
            MenuItem(Action.REINSTALL_ROOTFS, idle),
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
     * Compact single line, e.g. `Ready · Rootfs: alpine · 3.1 MB · / · proot · #ab12cd34`.
     * Never contains a newline (status messages are flattened); the UI ellipsizes it.
     */
    fun statusLine(state: ComputerUiState): String {
        val parts = buildList {
            add(statusLabel(state.status))
            add(CosRootfsInfo.statusLine(state.rootfsMode, state.rootfsUsedBytes))
            add(CosCwd.promptLabel(state.cwd).removePrefix("cos:").removeSuffix(" $"))
            add(runtimeLabel(state))
            if (state.sessionId.isNotBlank()) add("#" + state.sessionId.take(8))
        }
        return parts.joinToString(" · ").replace(Regex("\\s*[\\r\\n]+\\s*"), " ")
    }

    /** True when the input row can submit (Enter / send icon). */
    fun canRun(state: ComputerUiState): Boolean =
        state.enabled && !state.busy && state.input.isNotBlank()
}
