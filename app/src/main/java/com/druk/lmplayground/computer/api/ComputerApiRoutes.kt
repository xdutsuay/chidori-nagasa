package com.druk.lmplayground.computer.api

import com.druk.lmplayground.computer.desktop.DesktopBridge
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.ImageFormat
import com.druk.lmplayground.tools.CosConfirmPolicy
import com.druk.lmplayground.tools.CosToolDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest

/** One HTTP request as the router sees it. [target] is the raw request target (path plus optional `?query`). */
data class ApiRequest(
    val method: String,
    val target: String,
    val authorization: String?,
    val body: String = "",
    val remote: String = "",
)

class ApiResponse(val status: Int, val contentType: String, val body: ByteArray) {
    fun text(): String = String(body, Charsets.UTF_8)
}

/**
 * Read-only Computer API routes (slice 1). Pure: no sockets, so the server and tests share it.
 *
 * - Every request needs `Authorization: Bearer <token>` (scheme case-insensitive, constant-time
 *   compare). Missing, wrong, or an empty [token] → 401 `{"error":"unauthorized"}`, checked
 *   before routing so unknown paths are not revealed.
 * - Unknown path → 404 `{"error":"not found"}`; known path with a method other than GET → 405
 *   `{"error":"method not allowed"}`.
 * - [STATE_PATH] → 200 JSON: `desktop` is one of `unavailable` (no bridge), `not_installed`,
 *   `installing`, `stopped`, `starting`, `running`, `stopping`, `error`; `read_only` is true.
 *   When running it adds `width`, `height`, `depth`; when error it adds `message`.
 *   Never includes display names or file paths.
 * - [SCREENSHOT_PATH] → query `format` = `png` (default) or `jpeg`, case-insensitive, else 400;
 *   `scale` = float in [[MIN_SCALE], [MAX_SCALE]], default [DEFAULT_SCALE], else 400. If there is no
 *   bridge or the desktop is not running → 409 `{"error":"desktop not running"}`. Otherwise
 *   200 with the bridge's screenshot bytes and [PNG] or [JPEG]; if the bridge throws → 503
 *   `{"error":"screenshot failed"}`.
 * - All JSON responses use [JSON] content type.
 *
 * Slice 2, control ([EXEC_PATH], [GUI_PATH]; POST only, other methods → 405; GET-only paths
 * answer POST with 405):
 * - [control] null (the session was started without control) → 403 `{"error":"control not enabled"}`.
 * - Body must be a JSON object, else 400 `{"error":"body must be a JSON object"}`.
 * - [EXEC_PATH]: `command` must be a non-blank string, else 400. Then [ComputerControl.approve]
 *   with action `cos_exec` and the command as summary; false → 403 `{"error":"denied on phone"}`
 *   and nothing runs. Allowed → [ComputerControl.exec] with exactly `{"command": <command>}`
 *   (other body fields are dropped) and 200 with its JSON result as-is.
 * - [GUI_PATH]: `action` must be a non-blank string, else 400. No bridge or desktop not running
 *   → 409 `{"error":"desktop not running"}` without asking. Then [ComputerControl.approve] with
 *   action `cos_gui` and a non-blank summary; false → 403 as above. Allowed →
 *   [ComputerControl.gui] with the body unchanged and 200 with its JSON result as-is.
 * - Read-only `read_only` in the state JSON is `false` when [control] is non-null.
 *
 * Slice 3, pairing (only when [pairing] is non-null):
 * - A Bearer token is accepted if it equals a non-empty [token] or [PairingRegistry.authenticate]
 *   knows it. With [pairing] null, auth is unchanged.
 * - [PAIR_PATH] needs no Authorization. POST only (else 405). Body must be a JSON object with a
 *   string `code` (else 400); optional string `name`. [PairingRegistry.redeem] with
 *   [ApiRequest.remote] as the address: Ok → 200 `{"token":..., "client_id":...}`; Rejected →
 *   403 `{"error":"invalid or expired code"}`. With [pairing] null, [PAIR_PATH] is an ordinary
 *   unknown path (401 without a valid token, else 404).
 */
class ComputerApiRoutes(
    private val token: String,
    private val control: ComputerControl? = null,
    private val pairing: PairingRegistry? = null,
    private val bridge: () -> DesktopBridge?,
) {
    suspend fun handle(req: ApiRequest): ApiResponse {
        val path = req.target.substringBefore('?')
        val p = pairing
        if (p != null && path == PAIR_PATH) return pair(p, req)
        if (!authorized(req.authorization)) return error(401, "unauthorized")
        return when (path) {
            STATE_PATH, SCREENSHOT_PATH -> {
                if (req.method != "GET") return error(405, "method not allowed")
                if (path == STATE_PATH) state() else screenshot(query(req.target))
            }
            EXEC_PATH, GUI_PATH -> {
                if (req.method != "POST") return error(405, "method not allowed")
                val c = control ?: return error(403, "control not enabled")
                val body = runCatching { JSONObject(req.body) }.getOrNull()
                    ?: return error(400, "body must be a JSON object")
                if (path == EXEC_PATH) exec(c, body) else gui(c, body, req.body)
            }
            else -> error(404, "not found")
        }
    }

    private fun pair(p: PairingRegistry, req: ApiRequest): ApiResponse {
        if (req.method != "POST") return error(405, "method not allowed")
        val body = runCatching { JSONObject(req.body) }.getOrNull()
            ?: return error(400, "body must be a JSON object")
        val code = body.opt("code") as? String ?: return error(400, "code must be a string")
        val name = body.opt("name") as? String ?: ""
        return when (val r = p.redeem(code, name, req.remote)) {
            is Redeem.Ok -> ApiResponse(
                200,
                JSON,
                JSONObject().put("token", r.token).put("client_id", r.client.id).toString().toByteArray(Charsets.UTF_8),
            )
            Redeem.Rejected -> error(403, "invalid or expired code")
        }
    }

    private suspend fun exec(c: ComputerControl, body: JSONObject): ApiResponse {
        val command = (body.opt("command") as? String)?.takeIf { it.isNotBlank() }
            ?: return error(400, "command must be a non-blank string")
        if (!blocking { c.approve(CosToolDefaults.COS_EXEC, command) }) return error(403, "denied on phone")
        val result = blocking { c.exec(JSONObject().put("command", command).toString()) }
        return ApiResponse(200, JSON, result.toByteArray(Charsets.UTF_8))
    }

    private suspend fun gui(c: ComputerControl, body: JSONObject, raw: String): ApiResponse {
        val action = (body.opt("action") as? String)?.takeIf { it.isNotBlank() }
            ?: return error(400, "action must be a non-blank string")
        if (bridge()?.state?.value !is DesktopState.Running) return error(409, "desktop not running")
        val summary = CosConfirmPolicy.summary(CosToolDefaults.COS_GUI, raw).ifBlank { action }
        if (!blocking { c.approve(CosToolDefaults.COS_GUI, summary) }) return error(403, "denied on phone")
        val result = blocking { c.gui(raw) }
        return ApiResponse(200, JSON, result.toByteArray(Charsets.UTF_8))
    }

    private suspend fun <T> blocking(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

    private fun state(): ApiResponse {
        val json = JSONObject().put("read_only", control == null)
        when (val s = bridge()?.state?.value) {
            null -> json.put("desktop", "unavailable")
            DesktopState.NotInstalled -> json.put("desktop", "not_installed")
            is DesktopState.Installing -> json.put("desktop", "installing")
            DesktopState.Stopped -> json.put("desktop", "stopped")
            DesktopState.Starting -> json.put("desktop", "starting")
            DesktopState.Stopping -> json.put("desktop", "stopping")
            is DesktopState.Running -> json.put("desktop", "running")
                .put("width", s.width).put("height", s.height).put("depth", s.depth)
            is DesktopState.Error -> json.put("desktop", "error").put("message", s.message)
        }
        return ApiResponse(200, JSON, json.toString().toByteArray(Charsets.UTF_8))
    }

    private suspend fun screenshot(query: Map<String, String>): ApiResponse {
        val format = when (query["format"]?.lowercase() ?: "png") {
            "png" -> ImageFormat.PNG
            "jpeg" -> ImageFormat.JPEG
            else -> return error(400, "format must be png or jpeg")
        }
        val scale = query["scale"]?.let { raw ->
            raw.toFloatOrNull()?.takeIf { it in MIN_SCALE..MAX_SCALE }
                ?: return error(400, "scale must be between $MIN_SCALE and $MAX_SCALE")
        } ?: DEFAULT_SCALE
        val b = bridge()
        if (b == null || b.state.value !is DesktopState.Running) return error(409, "desktop not running")
        val bytes = try {
            b.screenshot(format, scale)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return error(503, "screenshot failed")
        }
        return ApiResponse(200, if (format == ImageFormat.PNG) PNG else JPEG, bytes)
    }

    private fun authorized(header: String?): Boolean {
        if (header == null) return false
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        val presented = header.substring(prefix.length).trim()
        val staticOk = token.isNotEmpty() &&
            MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
        return staticOk || pairing?.authenticate(presented) != null
    }

    private fun query(target: String): Map<String, String> {
        if ('?' !in target) return emptyMap()
        return target.substringAfter('?').split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun error(status: Int, message: String) =
        ApiResponse(status, JSON, JSONObject().put("error", message).toString().toByteArray(Charsets.UTF_8))

    companion object {
        const val STATE_PATH = "/v1/computer/state"
        const val SCREENSHOT_PATH = "/v1/computer/screenshot"
        const val EXEC_PATH = "/v1/computer/exec"
        const val GUI_PATH = "/v1/computer/gui"
        const val PAIR_PATH = "/v1/pair"
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 1f
        const val DEFAULT_SCALE = 1f
        const val JSON = "application/json"
        const val PNG = "image/png"
        const val JPEG = "image/jpeg"
    }
}
