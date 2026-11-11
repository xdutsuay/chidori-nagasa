package com.xdutsuay.chidori.computer

import android.content.Context

/**
 * Agent-facing entry for running commands inside COS without UI (backs `cos_exec`).
 *
 * The command line runs as `/bin/sh -c <line>` inside Alpine via proot, in the
 * guest workspace [CosConstants.GUEST_HOME] (`/root`), through the same
 * [LocalComputerRuntime.execStreaming] path as the Computer screen terminal.
 * Returns a structured result the chat/agent loop pastes into tool output.
 */
object CosAgentBridge {
    /** Rootfs-relative cwd for agent commands (guest `/root`). */
    const val AGENT_CWD = "root"

    /** stderr when Computer is off (matched by [com.xdutsuay.chidori.tools.CosExecFormatter.isCosDisabled]). */
    /** Terminator reason when the chat turn running `cos_exec` is stopped. */
    const val CHAT_STOP = "chat-stop"

    const val DISABLED_MESSAGE = "COS disabled — enable Computer first"

    /** stderr when Alpine is not installed and auto-setup failed. */
    fun notSetUpMessage(detail: String?): String =
        "COS is not set up yet" + (detail?.let { " ($it)" } ?: "") +
            ". Ask the user to open the Computer screen and tap \"Install rootfs\" to download Alpine."

    suspend fun exec(context: Context, commandLine: String): ComputerExecResult =
        exec(LocalComputerRuntime.create(context), commandLine)

    /** Same as [exec] on an explicit [runtime] (tests inject fake native libs / bootstrap). */
    internal suspend fun exec(runtime: LocalComputerRuntime, commandLine: String): ComputerExecResult {
        val initial = runtime.status()
        if (initial is ComputerStatus.Disabled) {
            return ComputerExecResult(127, "", DISABLED_MESSAGE)
        }
        if (!runtime.rootfsPresent()) {
            val s = runtime.ensureRootfs()
            if (!runtime.rootfsPresent()) {
                return ComputerExecResult(127, "", notSetUpMessage((s as? ComputerStatus.Error)?.message))
            }
        }
        val argv = CosShellLexer.toArgv(commandLine)
        if (argv.isEmpty()) {
            return ComputerExecResult(2, "", "empty command")
        }
        // Cancellation of the calling coroutine (chat Stop) terminates proot via
        // ProotTerminator with reason "chat-stop" and rethrows.
        return runtime.execStreaming(argv, AGENT_CWD, cancelReason = CHAT_STOP) { }
    }

    fun isAvailable(context: Context): Boolean {
        val runtime = LocalComputerRuntime.create(context)
        val s = runtime.status()
        return s is ComputerStatus.Ready || s is ComputerStatus.Running || s is ComputerStatus.MissingRootfs
    }
}
