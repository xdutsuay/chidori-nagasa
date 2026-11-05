package com.druk.lmplayground.tools

import org.json.JSONObject

/**
 * CP7 review-before-act policy for `cos_gui` and destructive `cos_exec`.
 *
 * Pure logic — prefs and UI live elsewhere. [ASK_ONCE] uses
 * [askOnceAlreadyApproved] so one approval covers the rest of the session.
 */
object CosConfirmPolicy {

    enum class Mode {
        AUTO,
        ASK_ONCE,
        ALWAYS,
        ;

        companion object {
            fun fromStorage(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
                "ask_once", "ask-once", "askonce" -> ASK_ONCE
                "always" -> ALWAYS
                else -> AUTO
            }
        }

        fun storageValue(): String = when (this) {
            AUTO -> "auto"
            ASK_ONCE -> "ask_once"
            ALWAYS -> "always"
        }
    }

    private val destructiveTokens = listOf(
        Regex("""(?:^|[;&|]\s*|\s)rm\s"""),
        Regex("""(?:^|[;&|]\s*|\s)rmdir\s"""),
        Regex("""(?:^|[;&|]\s*|\s)mkfs(?:\.\w+)?\s"""),
        Regex("""(?:^|[;&|]\s*|\s)dd\s"""),
        Regex("""(?:^|[;&|]\s*|\s)shutdown\b"""),
        Regex("""(?:^|[;&|]\s*|\s)reboot\b"""),
        Regex("""(?:^|[;&|]\s*|\s)truncate\s"""),
        Regex(""">\s*/"""),
    )

    fun isDestructiveCosExec(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty()) return false
        return destructiveTokens.any { it.containsMatchIn(c) }
    }

    /**
     * Whether the tool must wait for (or return) a user confirmation before running.
     */
    fun needsConfirm(
        toolName: String,
        argumentsJson: String,
        mode: Mode,
        askOnceAlreadyApproved: Boolean,
    ): Boolean {
        if (mode == Mode.AUTO) return false
        if (mode == Mode.ASK_ONCE && askOnceAlreadyApproved) return false
        return when (toolName) {
            CosToolDefaults.COS_GUI -> true
            CosToolDefaults.COS_EXEC -> {
                val command = CosExecFormatter.parseCommand(argumentsJson) ?: return false
                isDestructiveCosExec(command)
            }
            else -> false
        }
    }

    fun confirmationDeniedJson(toolName: String): String =
        JSONObject()
            .put("exit_code", 1)
            .put("needs_confirmation", true)
            .put(
                "error",
                "needs_confirmation: $toolName was not run; enable Auto or approve the confirm card",
            )
            .toString()
}
