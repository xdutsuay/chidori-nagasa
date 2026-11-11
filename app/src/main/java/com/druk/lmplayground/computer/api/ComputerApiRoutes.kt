package com.druk.lmplayground.computer.api

import com.druk.lmplayground.computer.desktop.DesktopBridge
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.ImageFormat
import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest

/** One HTTP request as the router sees it. [target] is the raw request target (path plus optional `?query`). */
data class ApiRequest(val method: String, val target: String, val authorization: String?)

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
 */
class ComputerApiRoutes(
    private val token: String,
    private val bridge: () -> DesktopBridge?,
) {
    suspend fun handle(req: ApiRequest): ApiResponse {
        if (!authorized(req.authorization)) return error(401, "unauthorized")
        val path = req.target.substringBefore('?')
        if (path != STATE_PATH && path != SCREENSHOT_PATH) return error(404, "not found")
        if (req.method != "GET") return error(405, "method not allowed")
        return if (path == STATE_PATH) state() else screenshot(query(req.target))
    }

    private fun state(): ApiResponse {
        val json = JSONObject().put("read_only", true)
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
        if (token.isEmpty() || header == null) return false
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        val got = header.substring(prefix.length).trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(got, token.toByteArray(Charsets.UTF_8))
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
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 1f
        const val DEFAULT_SCALE = 1f
        const val JSON = "application/json"
        const val PNG = "image/png"
        const val JPEG = "image/jpeg"
    }
}
