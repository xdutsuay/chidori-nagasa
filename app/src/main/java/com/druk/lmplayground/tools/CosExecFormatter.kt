package com.druk.lmplayground.tools

import com.druk.lmplayground.computer.ComputerExecResult
import org.json.JSONObject

/**
 * Pure parse/format helpers for [CosExecTool]. Kept free of Android runtime
 * so unit tests can cover argument extraction and model-facing JSON.
 */
object CosExecFormatter {
    const val DISABLED_ERROR =
        "Computer (COS) is disabled. Enable Computer (COS) in Settings before using cos_exec."

    fun parseCommand(arguments: String): String? {
        return try {
            val args = JSONObject(arguments)
            if (!args.has("command")) return null
            args.getString("command")
        } catch (_: Exception) {
            null
        }
    }

    fun formatResult(result: ComputerExecResult): String {
        val obj = JSONObject()
        obj.put("exit_code", result.exitCode)
        obj.put("stdout", result.stdout)
        obj.put("stderr", result.stderr)
        if (isCosDisabled(result)) {
            obj.put("error", DISABLED_ERROR)
        }
        return obj.toString()
    }

    fun errorJson(message: String): String {
        return JSONObject().put("error", message).toString()
    }

    fun isCosDisabled(result: ComputerExecResult): Boolean {
        return result.exitCode == 127 &&
            result.stderr.contains("COS disabled", ignoreCase = true)
    }
}
