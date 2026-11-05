package com.druk.lmplayground.computer

/**
 * Pure composer for the COS note appended to the system prompt of every local
 * chat session while Computer (COS) is enabled.
 *
 * Injected once per session by `ModelRuntime.createSessionWithParams`, i.e. in the
 * stable system-prompt prefix (KMA-318 preamble KV cache stays valid across
 * turns). With Computer disabled the user's prompt is returned unchanged.
 *
 * The state is re-evaluated every turn (`GenerationCoordinator`): Computer flag,
 * rootfs readiness and the effective `cos_exec` tool toggle ([liveState]). When it
 * differs from what the session was last told, a one-off [turnUpdate] is prepended
 * to that turn's model input (never to the stored chat message), so toggling the
 * tool or finishing setup takes effect without restarting the chat.
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

        /** Computer is on but `cos_exec` is not available in this chat (toggled off / no tool calling). */
        TOOL_OFF,

        /**
         * [READY] plus the COS desktop is running and `cos_screenshot`/`cos_gui` are
         * available this turn. Only reached through [liveState] (per-turn update),
         * never at session creation, so the cached system-prompt prefix is unchanged.
         */
        READY_DESKTOP,

        /** Desktop tools available, but `cos_exec` is not (toggled off in this chat). */
        DESKTOP_ONLY,
    }

    /** First line of every note; used to detect / avoid double injection. */
    const val HEADER = "## On-device computer (COS)"

    /** Desktop section shared by [State.READY_DESKTOP] and [State.DESKTOP_ONLY]. */
    private const val DESKTOP_SECTION =
        "A graphical desktop (X11, Openbox) is running in COS. Use `cos_screenshot` to see the " +
            "screen (the image is attached to its result; it reports the real width/height and " +
            "`scale`) and `cos_gui` to act on it: click/move at x,y in screen pixels (0,0 is the " +
            "top-left; multiply screenshot pixels by `scale`), type text, press keys such as " +
            "\"ctrl+l\" or \"Return\", and scroll. Take a new screenshot after acting to check the " +
            "result before the next step."

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
        State.TOOL_OFF ->
            "$HEADER\n" +
                "An on-device Alpine Linux computer (COS) exists, but the `cos_exec` tool is not " +
                "available in this chat (it is turned off, or this model cannot call tools), so you " +
                "cannot run commands there. Do not offer or attempt to call `cos_exec`. If the user " +
                "wants commands run, tell them to turn the `cos_exec` tool on in this chat's tool settings."
        State.READY_DESKTOP ->
            note(State.READY, workspace)!!
                .replace(", no GUI apps yet,", ",") + "\n" + DESKTOP_SECTION
        State.DESKTOP_ONLY ->
            "$HEADER\n" +
                "An on-device Alpine Linux computer (COS) runs on this phone. The `cos_exec` tool is " +
                "turned off in this chat, so do not call it. " + DESKTOP_SECTION
    }

    /**
     * Effective state for this turn: [base] from [stateFor], downgraded to [State.TOOL_OFF]
     * when Computer is on but `cos_exec` is not usable this turn, and upgraded to
     * [State.READY_DESKTOP] / [State.DESKTOP_ONLY] while the COS desktop is running and
     * the desktop tools (`cos_screenshot`, `cos_gui`) are offered this turn.
     */
    fun liveState(base: State, cosExecAvailable: Boolean, desktopToolsAvailable: Boolean = false): State = when {
        base == State.DISABLED -> State.DISABLED
        desktopToolsAvailable && base != State.NOT_SET_UP ->
            if (cosExecAvailable) State.READY_DESKTOP else State.DESKTOP_ONLY
        !cosExecAvailable -> State.TOOL_OFF
        else -> base
    }

    /** Marker opening a per-turn update (so it is recognisable in logs and tests). */
    const val UPDATE_OPEN = "[System update: on-device computer (COS) changed]"
    const val UPDATE_CLOSE = "[End of system update]"

    /**
     * One-off note for a turn whose live COS state differs from what the session was
     * last told ([announced]); null when nothing changed.
     */
    fun turnUpdate(announced: State, live: State): String? {
        if (announced == live) return null
        val body = note(live)
            ?: "The on-device computer (COS) has been turned off. `cos_exec` is no longer " +
            "available; do not offer or call it."
        return "$UPDATE_OPEN\n$body\nThis replaces any earlier COS instructions.\n$UPDATE_CLOSE"
    }

    /** [content] with [update] in front (blank-line separated), or unchanged when null. */
    fun withTurnUpdate(content: String, update: String?): String =
        if (update == null) content else "$update\n\n$content"

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
