package com.druk.lmplayground.computer.api

import android.content.Context
import com.druk.lmplayground.computer.CosAgentBridge
import com.druk.lmplayground.tools.CosConfirmGate
import com.druk.lmplayground.tools.CosConfirmPolicy
import com.druk.lmplayground.tools.CosExecTool
import com.druk.lmplayground.tools.CosGuiTool
import com.druk.lmplayground.tools.DesktopToolContext

/**
 * [ComputerControl] over the chat's `cos_exec` / `cos_gui` tools, so API actions get the same
 * argument validation and Activity log entries. The routes ask through the shared confirm gate
 * (the card shows in chat and in Developer options); the tools run with their own confirm off
 * so the user is asked exactly once per action.
 */
class ToolComputerControl(
    context: Context,
    private val gate: CosConfirmGate = CosConfirmGate.shared,
) : ComputerControl {
    private val app = context.applicationContext

    private val execTool = CosExecTool(
        runner = { command -> CosAgentBridge.exec(app, command) },
        confirmMode = { CosConfirmPolicy.Mode.AUTO },
        confirmGate = gate,
    )

    private val guiTool = CosGuiTool(
        desktop = DesktopToolContext.forApp(app),
        confirmMode = { CosConfirmPolicy.Mode.AUTO },
        confirmGate = gate,
    )

    override fun approve(action: String, summary: String): Boolean =
        gate.await("$APPROVAL_PREFIX$action", summary)

    override fun exec(argumentsJson: String): String = execTool.execute(argumentsJson)

    override fun gui(argumentsJson: String): String = guiTool.execute(argumentsJson)

    companion object {
        /** Prefix on the confirm card title so API requests are not mistaken for chat tool calls. */
        const val APPROVAL_PREFIX = "Computer API: "
    }
}
