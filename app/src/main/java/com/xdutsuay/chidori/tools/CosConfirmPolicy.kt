package com.xdutsuay.chidori.tools

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
            /** Unset or unknown values read as [ASK_ONCE]: acting on the desktop asks first by default. */
            fun fromStorage(raw: String?): Mode = when (raw?.trim()?.lowercase()) {
                "auto" -> AUTO
                "always" -> ALWAYS
                else -> ASK_ONCE
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

    /** Tool result when the user denied the confirm card, it timed out, or Stop was pressed. */
    fun declinedJson(toolName: String): String =
        JSONObject()
            .put("exit_code", 1)
            .put("declined", true)
            .put(
                "error",
                "declined: the user did not approve $toolName, so it was not run. " +
                    "Ask the user how to continue instead of retrying it.",
            )
            .toString()

    /** Short human summary of a pending call for the confirm card. */
    fun summary(toolName: String, argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return toolName
        return when (toolName) {
            CosToolDefaults.COS_EXEC -> args.optString("command")
            else -> args.keys().asSequence()
                .filter { args.opt(it).toString().isNotBlank() }
                .joinToString(" ") { "$it=${args.opt(it)}" }
        }.ifBlank { toolName }
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
