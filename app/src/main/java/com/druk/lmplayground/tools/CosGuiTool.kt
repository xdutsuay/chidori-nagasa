package com.druk.lmplayground.tools

import android.content.Context
import com.druk.lmplayground.computer.desktop.DesktopBridge
import com.druk.lmplayground.computer.desktop.DesktopGeometry
import com.druk.lmplayground.computer.desktop.input.XdotoolSession
import com.druk.lmplayground.computer.desktop.ShellQuote
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

/**
 * `cos_gui`: mouse/keyboard input on the COS desktop through `xdotool`, run by
 * [com.druk.lmplayground.computer.desktop.DesktopBridge.execInDesktop] (which
 * sets DISPLAY/XAUTHORITY for the cookie-protected Xvfb and goes through the CP1
 * runtime, so chat Stop terminates proot like `cos_exec`).
 *
 * Arguments are validated strictly by [CosGuiArgs] (enums, integer bounds
 * against the real screen, key charset, text length/control chars) into an
 * argv; the bridge's string command is built with [ShellQuote] so every argv
 * element reaches xdotool verbatim (no shell injection).
 *
 * Input-layer update: the argv now goes to the shared [XdotoolSession] (one
 * long-lived `xdotool -` per desktop session, same queue as the Desktop tab's
 * touch input). When the bridge has no stream support, or the session cannot
 * start, the session hands the call back and it runs through `execInDesktop` in
 * this call's coroutine as before. Chat Stop cancels either path (exit 130 /
 * `cancelled:true`); in session mode it closes the stream via ProotTerminator.
 */
class CosGuiTool internal constructor(
    private val desktop: DesktopToolContext,
    private val sessionFor: (DesktopBridge) -> XdotoolSession? = { XdotoolSession.shared(it) },
    private val confirmMode: () -> CosConfirmPolicy.Mode = { CosConfirmPolicy.Mode.AUTO },
    private val askOnceApproved: () -> Boolean = { CosConfirmSession.isAskOnceApproved() },
    private val confirmGate: CosConfirmGate = CosConfirmGate.shared,
) : Tool {
    constructor(context: Context, desktop: DesktopToolContext = DesktopToolContext.forApp(context)) : this(
        desktop = desktop,
        confirmMode = { StoragePreferences(context).getCosConfirmMode() },
    )

    override val name = CosToolDefaults.COS_GUI
    override val description =
        "Control the on-device Computer (COS) desktop with the mouse and keyboard (xdotool). " +
            "Actions: click (x, y, button left|middle|right, double), move (x, y), type (text), " +
            "key (keys, e.g. \"ctrl+l\", \"Return\", \"alt+F4\"), scroll (direction up|down|left|right + amount, " +
            "or dx/dy; optional x, y). Coordinates are screen pixels from cos_screenshot (0,0 = top-left). " +
            "Take a cos_screenshot after acting to check the result. Only available while the COS desktop is running."
    override val parametersSchema = SCHEMA

    private val inFlight = InFlightCalls()

    override fun isAvailable(): Boolean = desktop.isDesktopAvailable()

    override fun cancelInFlight() {
        confirmGate.cancelAll()
        inFlight.cancelAll()
    }

    override fun execute(arguments: String): String {
        if (!desktop.isComputerEnabled()) return CosDesktopFormatter.disabledJson(name)
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
        val bridge = desktop.bridge()
        val running = desktop.running()
        if (bridge == null || running == null) return CosDesktopFormatter.desktopNotRunningJson(name)

        val action = when (val p = CosGuiArgs.parse(arguments)) {
            is CosGuiArgs.Parsed.Ok -> p.value
            is CosGuiArgs.Parsed.Invalid -> return CosDesktopFormatter.invalidArgumentsJson(name, p.message)
        }
        val geometry = DesktopGeometry(running.width, running.height)
        val argv = when (val a = CosGuiArgs.toArgv(action, geometry, desktop.lastScreenshotScale)) {
            is CosGuiArgs.Parsed.Ok -> a.value
            is CosGuiArgs.Parsed.Invalid -> return CosDesktopFormatter.invalidArgumentsJson(name, a.message)
        }
        return try {
            val result = inFlight.run {
                val session = sessionFor(bridge)
                if (session != null) session.run(argv)
                else bridge.execInDesktop(ShellQuote.join(listOf(XDOTOOL) + argv), EXEC_TIMEOUT)
            }
            val out = JSONObject()
                .put("exit_code", result.exitCode)
                .put("action", action.name)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
            screenPoint(argv)?.let { (x, y) -> out.put("screen_x", x).put("screen_y", y) }
            when {
                CosDesktopFormatter.isCosDisabled(result) -> out.put("error", CosDesktopFormatter.disabledError(name))
                result.exitCode == 127 -> out.put("error", CosDesktopFormatter.missingProgramHint(XDOTOOL))
                result.exitCode != 0 -> out.put("error", "xdotool exited ${result.exitCode}")
            }
            out.toString()
        } catch (e: CancellationException) {
            CosDesktopFormatter.cancelledJson()
        } catch (e: Exception) {
            JSONObject()
                .put("exit_code", 1)
                .put("action", action.name)
                .put("error", "$name failed: ${e.cause?.message ?: e.message ?: "exec failed"}")
                .toString()
        }
    }

    /** Pointer position the argv moves to (first `mousemove x y`), for the result. */
    private fun screenPoint(argv: List<String>): Pair<Int, Int>? {
        val i = argv.indexOf("mousemove")
        if (i < 0 || i + 2 >= argv.size) return null
        val x = argv[i + 1].toIntOrNull() ?: return null
        val y = argv[i + 2].toIntOrNull() ?: return null
        return x to y
    }

    companion object {
        const val XDOTOOL = "xdotool"
        val EXEC_TIMEOUT = 20.seconds

        val SCHEMA: String = """
            {"type":"object","properties":{
            "action":{"type":"string","enum":["click","move","type","key","scroll"],"description":"What to do."},
            "x":{"type":"integer","minimum":0,"description":"Screen x in pixels (click, move; optional for scroll)."},
            "y":{"type":"integer","minimum":0,"description":"Screen y in pixels (click, move; optional for scroll)."},
            "button":{"type":"string","enum":["left","middle","right"],"description":"Mouse button for click (default left)."},
            "double":{"type":"boolean","description":"Double-click (default false)."},
            "text":{"type":"string","maxLength":${CosGuiArgs.MAX_TEXT_CHARS},"description":"Text to type (action=type). Newline and tab allowed, no other control characters."},
            "keys":{"type":"string","description":"Key combo(s) for action=key, space-separated, e.g. \"ctrl+l\", \"Return\", \"ctrl+shift+t\". X keysym names joined with '+'."},
            "direction":{"type":"string","enum":["up","down","left","right"],"description":"Scroll direction (action=scroll)."},
            "amount":{"type":"integer","minimum":1,"maximum":${CosGuiArgs.MAX_SCROLL},"description":"Wheel clicks for direction (default 3)."},
            "dx":{"type":"integer","minimum":-${CosGuiArgs.MAX_SCROLL},"maximum":${CosGuiArgs.MAX_SCROLL},"description":"Horizontal wheel clicks, >0 right (alternative to direction)."},
            "dy":{"type":"integer","minimum":-${CosGuiArgs.MAX_SCROLL},"maximum":${CosGuiArgs.MAX_SCROLL},"description":"Vertical wheel clicks, >0 down (alternative to direction)."},
            "space":{"type":"string","enum":["screen","screenshot"],"description":"Coordinate space of x/y: real screen pixels (default) or pixels of the last cos_screenshot image."}
            },"required":["action"]}
        """.trimIndent().replace("\n", "")
    }
}
