package com.druk.lmplayground.tools

import com.druk.lmplayground.computer.ComputerExecResult
import org.json.JSONObject

/** Model-facing JSON for `cos_screenshot` / `cos_gui` (same shapes as `cos_exec`). */
object CosDesktopFormatter {

    /** stderr the CP1 bridge uses when Computer is off (see CosAgentBridge). */
    const val COS_DISABLED_STDERR = "COS disabled — enable Computer first"

    fun disabledError(tool: String): String =
        "Computer (COS) is disabled. Enable Computer (COS) in Settings before using $tool."

    /** Same keys as `cos_exec`'s disabled result, with the tool's own name. */
    fun disabledJson(tool: String): String = JSONObject()
        .put("exit_code", 127)
        .put("stdout", "")
        .put("stderr", COS_DISABLED_STDERR)
        .put("error", disabledError(tool))
        .toString()

    const val DESKTOP_NOT_RUNNING =
        "The COS desktop is not running. The user must start it from the Desktop tab " +
            "(Computer). Use cos_exec for command-line work meanwhile."

    fun desktopNotRunningJson(tool: String): String = JSONObject()
        .put("exit_code", 1)
        .put("desktop_running", false)
        .put("error", "$tool: $DESKTOP_NOT_RUNNING")
        .toString()

    fun invalidArgumentsJson(tool: String, message: String): String = JSONObject()
        .put("exit_code", 2)
        .put("error", "$tool: invalid arguments: $message")
        .toString()

    fun errorJson(message: String): String = JSONObject().put("error", message).toString()

    /** Same as `cos_exec`'s stopped result: exit 130, `cancelled:true`, never exit 0. */
    fun cancelledJson(): String = CosExecFormatter.cancelledJson()

    fun isCosDisabled(result: ComputerExecResult): Boolean = CosExecFormatter.isCosDisabled(result)

    /** Shown when the command failed because xdotool is not installed (exit 127). */
    fun missingProgramHint(program: String): String =
        "$program is not installed in COS. Re-run the desktop install from the Desktop tab " +
            "(it installs: apk add xdotool)."
}
