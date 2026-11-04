package com.druk.lmplayground.tools

/**
 * Pure resolver for tool enablement, including the COS default-on rule.
 *
 * Every tool except `cos_exec` keeps the historic rule: per-model override if
 * set, else the global default (Settings → Tools), else OFF.
 *
 * `cos_exec` is ON by default whenever Computer (COS) is enabled — for new chats
 * and for existing ones (tools are re-resolved on every turn by
 * `GenerationCoordinator.runTurn`, so nothing has to be migrated). An explicit
 * user OFF (the chat's tool toggle, stored as a per-model override, or the
 * global switch in Settings → Tools) is always respected. When Computer is
 * disabled the tool is never offered, whatever the stored toggles say.
 */
object CosToolDefaults {
    const val COS_EXEC = "cos_exec"

    /**
     * @param override per-model override (the chat's tool toggle), null if never set
     * @param globalDefault stored Settings → Tools value, null if never set
     * @param computerEnabled `computer.enabled` from the Computer screen
     */
    fun resolve(
        toolName: String,
        override: Boolean?,
        globalDefault: Boolean?,
        computerEnabled: Boolean,
    ): Boolean {
        if (toolName != COS_EXEC) return override ?: globalDefault ?: false
        if (!computerEnabled) return false
        return override ?: globalDefault ?: true
    }

    /**
     * What Settings → Tools shows for the global switch: the stored value, else the
     * default (ON for `cos_exec`, OFF otherwise). Independent of Computer state so
     * the user's intent is visible even while Computer is off.
     */
    fun globalDisplay(toolName: String, globalDefault: Boolean?): Boolean =
        globalDefault ?: (toolName == COS_EXEC)
}
