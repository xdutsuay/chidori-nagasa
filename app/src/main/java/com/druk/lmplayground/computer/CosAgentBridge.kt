package com.druk.lmplayground.computer

import android.content.Context

/**
 * Agent-facing entry for running commands inside COS without UI.
 * Returns a structured result the chat/agent loop can paste into tool output.
 */
object CosAgentBridge {
    suspend fun exec(context: Context, commandLine: String): ComputerExecResult {
        val runtime = LocalComputerRuntime.create(context)
        if (runtime.status() is ComputerStatus.Disabled) {
            return ComputerExecResult(127, "", "COS disabled — enable Computer first")
        }
        if (runtime.status() is ComputerStatus.MissingRootfs) {
            runtime.ensureRootfs()
        }
        val argv = CosShellLexer.toArgv(commandLine)
        if (argv.isEmpty()) {
            return ComputerExecResult(2, "", "empty command")
        }
        return runtime.exec(argv)
    }

    fun isAvailable(context: Context): Boolean {
        val runtime = LocalComputerRuntime.create(context)
        val s = runtime.status()
        return s is ComputerStatus.Ready || s is ComputerStatus.Running || s is ComputerStatus.MissingRootfs
    }
}
