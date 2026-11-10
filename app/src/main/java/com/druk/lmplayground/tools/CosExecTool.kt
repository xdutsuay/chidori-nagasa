package com.druk.lmplayground.tools

import android.content.Context
import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.computer.CosAgentBridge
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional agent tool that runs a command line inside on-device Computer (COS)
 * via [CosAgentBridge]. Off by default like other tools; requires Computer
 * enabled in Settings.
 *
 * Chat Stop: [cancelInFlight] cancels the coroutine running the command. The
 * runtime's wait is interruptible, so proot is torn down through [ProotTerminator]
 * (reason `chat-stop`) and the tool returns [CosExecFormatter.cancelledJson].
 */
class CosExecTool internal constructor(
    private val runner: suspend (String) -> ComputerExecResult,
    private val confirmMode: () -> CosConfirmPolicy.Mode = { CosConfirmPolicy.Mode.AUTO },
    private val askOnceApproved: () -> Boolean = { CosConfirmSession.isAskOnceApproved() },
    private val confirmGate: CosConfirmGate = CosConfirmGate.shared,
) : Tool {
    constructor(context: Context) : this(
        runner = { command -> CosAgentBridge.exec(context, command) },
        confirmMode = {
            StoragePreferences(context).getCosConfirmMode()
        },
    )

    override val name = "cos_exec"
    override val description =
        "Run a shell command inside the on-device Computer (COS) Alpine environment. " +
            "Returns exit_code, stdout, and stderr. Requires Computer (COS) enabled in Settings."
    override val parametersSchema =
        """{"type":"object","properties":{"command":{"type":"string","description":"Shell command line to run inside COS, e.g. 'uname -a' or 'ls /'."}},"required":["command"]}"""

    private val inFlight = ConcurrentHashMap.newKeySet<Job>()

    override fun cancelInFlight() {
        confirmGate.cancelAll()
        inFlight.forEach { it.cancel(CancellationException(CosAgentBridge.CHAT_STOP)) }
    }

    override fun execute(arguments: String): String {
        return try {
            val command = CosExecFormatter.parseCommand(arguments)
                ?: return CosExecFormatter.errorJson("Missing required argument: command")
            if (command.isBlank()) {
                return CosExecFormatter.errorJson("empty command")
            }
            val mode = confirmMode()
            if (CosConfirmPolicy.needsConfirm(
                    toolName = name,
                    argumentsJson = arguments,
                    mode = mode,
                    askOnceAlreadyApproved = askOnceApproved(),
                )
            ) {
                if (!confirmGate.await(name, CosConfirmPolicy.summary(name, arguments))) {
                    return CosConfirmPolicy.declinedJson(name)
                }
                if (mode == CosConfirmPolicy.Mode.ASK_ONCE) CosConfirmSession.markAskOnceApproved()
            }
            val result = runBlocking {
                val job = async(Dispatchers.IO) { runner(command) }
                inFlight.add(job)
                try {
                    job.await()
                } finally {
                    inFlight.remove(job)
                }
            }
            CosExecFormatter.formatResult(result)
        } catch (e: CancellationException) {
            CosExecFormatter.cancelledJson()
        } catch (e: Exception) {
            val message = (e.cause?.message ?: e.message ?: "Execution failed")
                .replace("\"", "'")
            CosExecFormatter.errorJson(message)
        }
    }
}
