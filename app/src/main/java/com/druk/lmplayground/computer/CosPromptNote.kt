package com.druk.lmplayground.computer

/**
 * Pure composer for the COS note appended to the system prompt of every local
 * chat session while Computer (COS) is enabled.
 *
 * Injected once per session by `ModelRuntime.createSessionWithParams`, i.e. in the
 * stable system-prompt prefix (KMA-318 preamble KV cache stays valid across
 * turns); it is never repeated per user turn. With Computer disabled the user's
 * prompt is returned unchanged.
 */
object CosPromptNote {

    /** COS readiness as seen when a session is created. */
    enum class State {
        /** Computer is off: no note, `cos_exec` is not offered. */
        DISABLED,

        /** Computer is on but Alpine isn't installed yet. */
        NOT_SET_UP,

        /** Computer is on and the pinned Alpine rootfs is installed. */
        READY,
    }

    /** First line of every note; used to detect / avoid double injection. */
    const val HEADER = "## On-device computer (COS)"

    /** The note body for [state], or null for [State.DISABLED]. */
    fun note(state: State, workspace: String = CosConstants.GUEST_HOME): String? = when (state) {
        State.DISABLED -> null
        State.READY ->
            "$HEADER\n" +
                "You have a real Linux computer on this phone: Alpine Linux " +
                "${CosConstants.ALPINE_SERIES} running via proot. Use the `cos_exec` tool to run shell " +
                "commands there: inspect and edit files, run scripts, and install packages with " +
                "`apk add <pkg>`. Commands run as root with `/bin/sh -c` in the workspace `$workspace` " +
                "(it persists between calls). Each call is non-interactive and limited to " +
                "${CosConstants.EXEC_TIMEOUT_MS / 1000} s. Limits: no systemd/services, no GUI apps " +
                "yet, no access to the phone's own files outside COS."
        State.NOT_SET_UP ->
            "$HEADER\n" +
                "An on-device Alpine Linux computer (COS) is enabled but not set up yet. If the user " +
                "wants you to run commands, tell them to open the Computer screen and tap " +
                "\"Install rootfs\". Once installed, `cos_exec` runs shell commands there " +
                "(workspace `$workspace`, packages via `apk add`)."
    }

    /**
     * [userPrompt] with the note for [state] appended (blank-line separated).
     * Idempotent: a prompt that already contains [HEADER] is returned unchanged.
     */
    fun compose(userPrompt: String, state: State, workspace: String = CosConstants.GUEST_HOME): String {
        val n = note(state, workspace) ?: return userPrompt
        if (userPrompt.contains(HEADER)) return userPrompt
        return if (userPrompt.isBlank()) n else userPrompt.trimEnd() + "\n\n" + n
    }

    /** State from the Computer flag and whether a verified rootfs is present. */
    fun stateOf(computerEnabled: Boolean, rootfsReady: Boolean): State = when {
        !computerEnabled -> State.DISABLED
        !rootfsReady -> State.NOT_SET_UP
        else -> State.READY
    }

    /**
     * System prompt a local session is created with: [compose]d with the COS note for
     * chat sessions, verbatim for non-chat one-shots ([chat] = false).
     */
    fun sessionSystemPrompt(userPrompt: String, chat: Boolean, state: State): String =
        if (chat) compose(userPrompt, state) else userPrompt

    /**
     * Live COS state from prefs + the rootfs ready marker (no network, no proot).
     * Any failure reads as [State.DISABLED] so prompt building never throws.
     */
    fun stateFor(context: android.content.Context): State = runCatching {
        val prefs = context.getSharedPreferences(CosConstants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val enabled = ComputerPrefs.isComputerEnabled(prefs)
        stateOf(enabled, enabled && LocalComputerRuntime.create(context).rootfsPresent())
    }.getOrDefault(State.DISABLED)
}
