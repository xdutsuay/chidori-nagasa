package com.druk.lmplayground.computer.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.druk.lmplayground.R
import com.druk.lmplayground.computer.desktop.input.DesktopKeyboardBar
import com.druk.lmplayground.computer.desktop.input.DesktopPointerMode
import com.druk.lmplayground.computer.desktop.input.DesktopRotation
import com.druk.lmplayground.computer.desktop.input.InputCommand
import com.druk.lmplayground.computer.desktop.input.LetterboxTransform
import com.druk.lmplayground.computer.desktop.input.RotatingMapper
import com.druk.lmplayground.computer.desktop.input.XdotoolSession
import com.druk.lmplayground.computer.desktop.input.desktopInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * CINDI M2 viewer: polls Xvfb `-fbdir` (adaptive, ~15 fps while changing), pinch-zoom /
 * pan, Direct vs Trackpad pointer modes, quarter-turn rotate, Fit / Screenshot /
 * Fullscreen, and gestures through [XdotoolSession].
 */
@Composable
fun DesktopViewer(
    bridge: DesktopBridge,
    running: DesktopState.Running,
    modifier: Modifier = Modifier,
    onScreenshot: () -> Unit = {},
) {
    val session = remember(bridge) { XdotoolSession.shared(bridge) }

    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameVersion by remember { mutableIntStateOf(0) }
    val pacer = remember { AdaptiveFramePacer(minIntervalMs = FRAME_INTERVAL_MS, maxIntervalMs = IDLE_FRAME_INTERVAL_MS) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pointerMode by remember { mutableStateOf(DesktopPointerMode.DIRECT) }
    var rotation by remember { mutableStateOf(DesktopRotation.NONE) }
    var fullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(running.framebufferHostFile, lifecycle) {
        val detector = FrameChangeDetector()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            detector.reset()
            while (isActive) {
                val image = withContext(Dispatchers.IO) {
                    runCatching { XwdDecoder.decode(running.framebufferHostFile) }
                        .getOrNull()
                        ?.takeIf { detector.isNew(it.width, it.height, it.argb) }
                }
                if (image != null) {
                    val current = frame
                    if (current != null && !current.isRecycled &&
                        current.width == image.width && current.height == image.height
                    ) {
                        current.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
                    } else {
                        val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                        bmp.setPixels(image.argb, 0, image.width, 0, 0, image.width, image.height)
                        frame = bmp
                        current?.recycle()
                    }
                    frameVersion++
                }
                delay(pacer.onFrame(changed = image != null))
            }
        }
    }

    val bmp = frame
    val imageBitmap = remember(bmp, frameVersion) { bmp?.takeIf { !it.isRecycled }?.asImageBitmap() }
    val send: (InputCommand) -> Unit = {
        pacer.onInput()
        session.send(it)
    }
    val fit = {
        zoom = 1f
        pan = Offset.Zero
    }

    @Composable
    fun LiveSurface(surfaceModifier: Modifier, overlay: @Composable () -> Unit) {
        ViewerSurface(
            image = imageBitmap,
            running = running,
            zoom = zoom,
            pan = pan,
            rotation = rotation,
            pointerMode = pointerMode,
            onTransform = { panChange, zoomChange ->
                zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                pan += panChange
            },
            sink = send,
            modifier = surfaceModifier,
            overlay = overlay,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveSurface(
            Modifier
                .fillMaxWidth()
                .aspectRatio(running.width.toFloat() / running.height.toFloat().coerceAtLeast(1f))
                .clip(RoundedCornerShape(18.dp)),
        ) {
            ViewerOverlayButtons(
                onFit = fit,
                onScreenshot = onScreenshot,
                fullscreenIcon = Icons.Outlined.Fullscreen,
                fullscreenLabel = stringResource(R.string.cos_viewer_fullscreen),
                onFullscreen = { fullscreen = true },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = pointerMode == DesktopPointerMode.DIRECT,
                    onClick = { pointerMode = DesktopPointerMode.DIRECT },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag(TAG_MODE_DIRECT),
                ) { Text(stringResource(R.string.cos_desktop_mode_direct)) }
                SegmentedButton(
                    selected = pointerMode == DesktopPointerMode.TRACKPAD,
                    onClick = { pointerMode = DesktopPointerMode.TRACKPAD },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Outlined.Mouse, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag(TAG_MODE_TRACKPAD),
                ) { Text(stringResource(R.string.cos_desktop_mode_trackpad)) }
            }
            TextButton(
                onClick = {
                    rotation = rotation.next()
                    fit()
                },
                modifier = Modifier.testTag(TAG_ROTATE),
            ) {
                Icon(Icons.Outlined.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.cos_desktop_rotate, rotation.degrees),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        DesktopKeyboardBar(sink = send)
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                LiveSurface(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    ViewerOverlayButtons(
                        onFit = fit,
                        onScreenshot = onScreenshot,
                        fullscreenIcon = Icons.Outlined.CloseFullscreen,
                        fullscreenLabel = stringResource(R.string.cos_viewer_exit_fullscreen),
                        onFullscreen = { fullscreen = false },
                    )
                }
                DesktopKeyboardBar(sink = send)
            }
        }
    }
}

@Composable
private fun ViewerSurface(
    image: ImageBitmap?,
    running: DesktopState.Running,
    zoom: Float,
    pan: Offset,
    rotation: DesktopRotation,
    pointerMode: DesktopPointerMode,
    onTransform: (Offset, Float) -> Unit,
    sink: (InputCommand) -> Unit,
    modifier: Modifier,
    overlay: @Composable () -> Unit,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { viewSize = it },
    ) {
        if (image != null) {
            val vw = viewSize.width.coerceAtLeast(1).toFloat()
            val vh = viewSize.height.coerceAtLeast(1).toFloat()
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.cos_desktop_screenshot_cd),
                modifier = Modifier
                    .fillMaxSize()
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
                        sink = sink,
                        pointerMode = pointerMode,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            onTransform(panChange, zoomChange)
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
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        ) { overlay() }
    }
}

@Composable
private fun ViewerOverlayButtons(
    onFit: () -> Unit,
    onScreenshot: () -> Unit,
    fullscreenIcon: ImageVector,
    fullscreenLabel: String,
    onFullscreen: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OverlayButton(Icons.Outlined.FitScreen, stringResource(R.string.cos_viewer_fit), onFit, TAG_FIT)
            OverlayButton(Icons.Outlined.CameraAlt, stringResource(R.string.cos_viewer_screenshot), onScreenshot, TAG_SHOT)
            OverlayButton(fullscreenIcon, fullscreenLabel, onFullscreen, TAG_FULLSCREEN)
        }
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, tag: String) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
        ),
        modifier = Modifier
            .size(40.dp)
            .testTag(tag),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

private const val FRAME_INTERVAL_MS = 66L
private const val IDLE_FRAME_INTERVAL_MS = 500L
internal const val TAG_MODE_DIRECT = "cos_desktop_mode_direct"
internal const val TAG_MODE_TRACKPAD = "cos_desktop_mode_trackpad"
internal const val TAG_ROTATE = "cos_desktop_rotate"
internal const val TAG_FIT = "cos_viewer_fit"
internal const val TAG_SHOT = "cos_viewer_shot"
internal const val TAG_FULLSCREEN = "cos_viewer_fullscreen"
