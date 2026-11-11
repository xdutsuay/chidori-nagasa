package com.xdutsuay.chidori.tools

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
 *
 * The CP2 desktop tools (`cos_screenshot`, `cos_gui`) follow the same rule
 * ([COS_TOOLS]). They are additionally only advertised while the COS desktop is
 * running (`Tool.isAvailable`), which is not a preference and is not resolved here.
 */
object CosToolDefaults {
    const val COS_EXEC = "cos_exec"
    const val COS_SCREENSHOT = "cos_screenshot"
    const val COS_GUI = "cos_gui"

    /** Tools that need Computer (COS) and default ON while it is enabled. */
    val COS_TOOLS: Set<String> = setOf(COS_EXEC, COS_SCREENSHOT, COS_GUI)

    /** Tools that need the running COS desktop. */
    val DESKTOP_TOOLS: Set<String> = setOf(COS_SCREENSHOT, COS_GUI)

    const val LIBRARY_SEARCH = "library_search"

    /**
     * Non-COS tools that default ON: they only read the user's own on-phone data
     * and are hidden by [Tool.isAvailable] until there is something to read.
     */
    val DEFAULT_ON_TOOLS: Set<String> = setOf(LIBRARY_SEARCH)

    fun isCosTool(toolName: String): Boolean = toolName in COS_TOOLS

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
        if (toolName !in COS_TOOLS) return override ?: globalDefault ?: (toolName in DEFAULT_ON_TOOLS)
        if (!computerEnabled) return false
        return override ?: globalDefault ?: true
    }

    /**
     * What Settings → Tools shows for the global switch: the stored value, else the
     * default (ON for `cos_exec`, OFF otherwise). Independent of Computer state so
     * the user's intent is visible even while Computer is off.
     */
    fun globalDisplay(toolName: String, globalDefault: Boolean?): Boolean =
        globalDefault ?: (toolName in COS_TOOLS || toolName in DEFAULT_ON_TOOLS)
}
