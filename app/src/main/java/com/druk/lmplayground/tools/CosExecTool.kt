package com.druk.lmplayground.tools

import android.content.Context
import com.druk.lmplayground.computer.CosAgentBridge
import kotlinx.coroutines.runBlocking

/**
 * Optional agent tool that runs a command line inside on-device Computer (COS)
 * via [CosAgentBridge]. Off by default like other tools; requires Computer
 * enabled in Settings.
 */
class CosExecTool(private val context: Context) : Tool {
    override val name = "cos_exec"
    override val description =
        "Run a shell command inside the on-device Computer (COS) Alpine environment. " +
            "Returns exit_code, stdout, and stderr. Requires Computer (COS) enabled in Settings."
    override val parametersSchema =
        """{"type":"object","properties":{"command":{"type":"string","description":"Shell command line to run inside COS, e.g. 'uname -a' or 'ls /'."}},"required":["command"]}"""

    override fun execute(arguments: String): String {
        return try {
            val command = CosExecFormatter.parseCommand(arguments)
                ?: return CosExecFormatter.errorJson("Missing required argument: command")
            if (command.isBlank()) {
                return CosExecFormatter.errorJson("empty command")
            }
            val result = runBlocking {
                CosAgentBridge.exec(context, command)
            }
            CosExecFormatter.formatResult(result)
        } catch (e: Exception) {
            val message = (e.cause?.message ?: e.message ?: "Execution failed")
                .replace("\"", "'")
            CosExecFormatter.errorJson(message)
        }
    }
}
