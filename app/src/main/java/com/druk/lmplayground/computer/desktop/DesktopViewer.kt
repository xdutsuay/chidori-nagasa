package com.druk.lmplayground.computer.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.computer.desktop.input.DesktopKeyboardBar
import com.druk.lmplayground.computer.desktop.input.LetterboxTransform
import com.druk.lmplayground.computer.desktop.input.XdotoolSession
import com.druk.lmplayground.computer.desktop.input.desktopInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * CINDI M2 viewer: poll Xvfb `-fbdir` ~15 fps, pinch-zoom / pan, and route gestures
 * through [XdotoolSession] (long-lived `xdotool -` when [DesktopBridge.openDesktopStream]
 * is available).
 */
@Composable
fun DesktopViewer(
    bridge: DesktopBridge,
    running: DesktopState.Running,
    modifier: Modifier = Modifier,
) {
    // Shared with cos_gui so one long-lived xdotool serves both the viewer and chat.
    val session = remember(bridge) { XdotoolSession.shared(bridge) }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(running.framebufferHostFile) {
        while (isActive) {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val image = XwdDecoder.decode(running.framebufferHostFile)
                    Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
                }.getOrNull()
            }
            if (bmp != null) {
                frame?.recycle()
                frame = bmp
            }
            delay(FRAME_INTERVAL_MS)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 360.dp)
                .aspectRatio(running.width.toFloat() / running.height.toFloat().coerceAtLeast(1f))
                .background(Color.Black)
                .onSizeChanged { viewSize = it },
        ) {
            val bmp = frame
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "CINDI desktop",
                    modifier = Modifier
                        .fillMaxWidth()
                        .desktopInput(
                            mapper = {
                                LetterboxTransform(
                                    viewWidth = viewSize.width.coerceAtLeast(1).toFloat(),
                                    viewHeight = viewSize.height.coerceAtLeast(1).toFloat(),
                                    desktopWidth = running.width,
                                    desktopHeight = running.height,
                                    zoom = zoom,
                                    panX = pan.x,
                                    panY = pan.y,
                                )
                            },
                            sink = { session.send(it) },
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                pan += panChange
                            }
                        }
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = pan.x,
                            translationY = pan.y,
                        ),
                )
            }
        }
        DesktopKeyboardBar(sink = { session.send(it) })
    }
}

private const val FRAME_INTERVAL_MS = 66L // ~15 fps
