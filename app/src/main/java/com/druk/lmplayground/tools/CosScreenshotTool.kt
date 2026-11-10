package com.druk.lmplayground.tools

import android.content.Context
import com.druk.lmplayground.computer.activity.ComputerActivity
import com.druk.lmplayground.computer.activity.Kind
import com.druk.lmplayground.computer.activity.Outcome
import com.druk.lmplayground.computer.desktop.ImageFormat
import com.druk.lmplayground.computer.desktop.PngInfo
import com.druk.lmplayground.computer.desktop.ScreenshotScaling
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * `cos_screenshot`: captures the COS desktop (Xvfb framebuffer, via
 * [com.druk.lmplayground.computer.desktop.DesktopBridge.screenshot]) and hands
 * the image to the model as an attachment of this tool result (see
 * [ToolImageAttachments]); the JSON result carries only metadata.
 *
 * The image's longest edge is capped at `max_edge` (default/maximum 1280);
 * `scale` in the result maps image pixels back to screen pixels.
 *
 * Gating mirrors `cos_exec`: Computer off -> cos_exec-shaped disabled error;
 * desktop not running -> not advertised ([isAvailable]) and an explicit error if
 * called anyway. Chat Stop -> exit 130 / cancelled:true.
 */
class CosScreenshotTool internal constructor(
    private val desktop: DesktopToolContext,
    private val images: ToolImageAttachments,
) : Tool {
    constructor(context: Context, images: ToolImageAttachments, desktop: DesktopToolContext = DesktopToolContext.forApp(context)) :
        this(desktop, images)

    override val name = CosToolDefaults.COS_SCREENSHOT
    override val description =
        "Take a screenshot of the on-device Computer (COS) desktop. The image is attached to this " +
            "result for you to look at. Returns the real screen width/height and 'scale' " +
            "(screen pixels per image pixel); pass screen coordinates to cos_gui. " +
            "Only available while the COS desktop is running."
    override val parametersSchema =
        """{"type":"object","properties":{"max_edge":{"type":"integer","minimum":$MIN_EDGE,"maximum":${ScreenshotScaling.DEFAULT_MAX_EDGE},"description":"Longest edge of the returned image in pixels (default ${ScreenshotScaling.DEFAULT_MAX_EDGE}). The screen is downscaled to fit; coordinates for cos_gui stay in real screen pixels."}},"required":[]}"""

    private val inFlight = InFlightCalls()

    override fun isAvailable(): Boolean = desktop.isDesktopAvailable()

    override fun cancelInFlight() = inFlight.cancelAll()

    override fun execute(arguments: String): String {
        if (!desktop.isComputerEnabled()) return recordGateFailure(null) { CosDesktopFormatter.disabledJson(name) }
        val bridge = desktop.bridge()
        val running = desktop.running()
        if (bridge == null || running == null) return recordGateFailure(null) { CosDesktopFormatter.desktopNotRunningJson(name) }
        val maxEdge = when (val m = parseMaxEdge(arguments)) {
            is Result.Ok -> m.value
            is Result.Bad -> return recordGateFailure(m.message) { CosDesktopFormatter.invalidArgumentsJson(name, m.message) }
        }
        val fit = ScreenshotScaling.fit(running.width, running.height, maxEdge)
        val requestScale = (1.0 / fit.scale).toFloat()
        return try {
            var format = ImageFormat.PNG
            var bytes = inFlight.run { bridge.screenshot(ImageFormat.PNG, requestScale) }
            if (bytes.size > MAX_INLINE_IMAGE_BYTES) {
                // Keep the binder transaction to the inference service small.
                format = ImageFormat.JPEG
                bytes = inFlight.run { bridge.screenshot(ImageFormat.JPEG, requestScale) }
            }
            if (bytes.isEmpty()) return failure("empty screenshot")
            val size = PngInfo.size(bytes)
            val imageW = size?.width ?: fit.width
            val imageH = size?.height ?: fit.height
            val scale = running.width.toDouble() / imageW
            desktop.lastScreenshotScale = scale
            val id = images.put(bytes, if (format == ImageFormat.PNG) "image/png" else "image/jpeg")
            ComputerActivity.log
                .record(kind = Kind.SCREENSHOT, summary = "", outcome = Outcome.OK)
                .let { ComputerActivity.attachImage(it.id, bytes) }
            JSONObject()
                .put("exit_code", 0)
                .put("width", running.width)
                .put("height", running.height)
                .put("image_width", imageW)
                .put("image_height", imageH)
                .put("scale", ScreenshotScaling.displayScale(scale))
                .put("format", format.name.lowercase())
                .put("bytes", bytes.size)
                .put("image_id", id)
                .put(
                    "note",
                    if (scale > 1.0) {
                        "Image is downscaled. cos_gui takes screen pixels: screen_x = round(image_x * scale), " +
                            "or pass space=\"screenshot\" to cos_gui to use image pixels."
                    } else {
                        "Image pixels equal screen pixels; use them directly with cos_gui."
                    },
                )
                .toString()
        } catch (e: CancellationException) {
            CosDesktopFormatter.cancelledJson()
        } catch (e: Exception) {
            failure((e.cause?.message ?: e.message ?: "capture failed"))
        }
    }

    private fun failure(message: String): String = JSONObject()
        .put("exit_code", 1)
        .put("error", "$name failed: $message")
        .toString()

    /**
     * Records a failed (gating / bad-arguments) call in the activity log — summary
     * stays blank so the panel shows the kind label — and returns the model-facing
     * [resultJson].
     */
    private fun recordGateFailure(detail: String?, resultJson: () -> String): String {
        ComputerActivity.log.record(
            kind = Kind.SCREENSHOT,
            summary = "",
            outcome = Outcome.ERROR,
            detail = detail,
        )
        return resultJson()
    }

    private sealed class Result {
        data class Ok(val value: Int) : Result()
        data class Bad(val message: String) : Result()
    }

    private fun parseMaxEdge(arguments: String): Result {
        val json = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return Result.Bad("arguments must be a JSON object")
        if (!json.has("max_edge") || json.isNull("max_edge")) return Result.Ok(ScreenshotScaling.DEFAULT_MAX_EDGE)
        val v = when (val raw = json.get("max_edge")) {
            is Int -> raw
            is Double -> if (raw % 1.0 == 0.0) raw.toInt() else return Result.Bad("'max_edge' must be an integer")
            is String -> raw.trim().toIntOrNull() ?: return Result.Bad("'max_edge' must be an integer")
            else -> return Result.Bad("'max_edge' must be an integer")
        }
        if (v !in MIN_EDGE..ScreenshotScaling.DEFAULT_MAX_EDGE) {
            return Result.Bad("'max_edge' must be $MIN_EDGE..${ScreenshotScaling.DEFAULT_MAX_EDGE}")
        }
        return Result.Ok(v)
    }

    companion object {
        const val MIN_EDGE = 256

        /** Above this a PNG is re-requested as JPEG (binder transaction budget is ~1 MB). */
        const val MAX_INLINE_IMAGE_BYTES = 700 * 1024
    }
}
