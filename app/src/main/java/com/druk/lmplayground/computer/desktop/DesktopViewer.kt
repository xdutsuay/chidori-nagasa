package com.druk.lmplayground.computer.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.computer.desktop.input.DesktopKeyboardBar
import com.druk.lmplayground.computer.desktop.input.DesktopPointerMode
import com.druk.lmplayground.computer.desktop.input.DesktopRotation
import com.druk.lmplayground.computer.desktop.input.LetterboxTransform
import com.druk.lmplayground.computer.desktop.input.RotatingMapper
import com.druk.lmplayground.computer.desktop.input.XdotoolSession
import com.druk.lmplayground.computer.desktop.input.desktopInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * CINDI M2 viewer: poll Xvfb `-fbdir` ~15 fps, pinch-zoom / pan, Direct vs Trackpad
 * pointer modes, quarter-turn rotate, and gestures through [XdotoolSession].
 */
@Composable
fun DesktopViewer(
    bridge: DesktopBridge,
    running: DesktopState.Running,
    modifier: Modifier = Modifier,
) {
    val session = remember(bridge) { XdotoolSession.shared(bridge) }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pointerMode by remember { mutableStateOf(DesktopPointerMode.DIRECT) }
    var rotation by remember { mutableStateOf(DesktopRotation.NONE) }

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
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = pointerMode == DesktopPointerMode.DIRECT,
                onClick = { pointerMode = DesktopPointerMode.DIRECT },
                label = { Text(stringResource(R.string.cos_desktop_mode_direct)) },
                modifier = Modifier.testTag(TAG_MODE_DIRECT),
            )
            FilterChip(
                selected = pointerMode == DesktopPointerMode.TRACKPAD,
                onClick = { pointerMode = DesktopPointerMode.TRACKPAD },
                label = { Text(stringResource(R.string.cos_desktop_mode_trackpad)) },
                modifier = Modifier.testTag(TAG_MODE_TRACKPAD),
            )
            TextButton(
                onClick = {
                    rotation = rotation.next()
                    zoom = 1f
                    pan = Offset.Zero
                },
                modifier = Modifier.testTag(TAG_ROTATE),
            ) {
                Text(stringResource(R.string.cos_desktop_rotate, rotation.degrees))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 360.dp)
                .aspectRatio(running.width.toFloat() / running.height.toFloat().coerceAtLeast(1f))
                .background(Color.Black)
                .clipToBounds()
                .onSizeChanged { viewSize = it },
        ) {
            val bmp = frame
            if (bmp != null && !bmp.isRecycled) {
                val vw = viewSize.width.coerceAtLeast(1).toFloat()
                val vh = viewSize.height.coerceAtLeast(1).toFloat()
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.cos_desktop_screenshot_cd),
                    modifier = Modifier
                        .fillMaxWidth()
                        .desktopInput(
                            mapper = {
                                val letterbox = LetterboxTransform(
                                    viewWidth = vw,
                                    viewHeight = vh,
                                    desktopWidth = running.width,
                                    desktopHeight = running.height,
                                    zoom = zoom,
                                    panX = pan.x,
                                    panY = pan.y,
                                )
                                RotatingMapper(letterbox, rotation, vw, vh)
                            },
                            sink = { session.send(it) },
                            pointerMode = pointerMode,
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
                            rotationZ = rotation.degrees.toFloat(),
                        ),
                )
            }
        }
        DesktopKeyboardBar(sink = { session.send(it) })
    }
}

private const val FRAME_INTERVAL_MS = 66L
internal const val TAG_MODE_DIRECT = "cos_desktop_mode_direct"
internal const val TAG_MODE_TRACKPAD = "cos_desktop_mode_trackpad"
internal const val TAG_ROTATE = "cos_desktop_rotate"
