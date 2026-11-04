package com.druk.lmplayground.computer.desktop

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.computer.LocalComputerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the temporary Desktop debug section shows. */
data class DesktopPanelState(
    val state: DesktopState = DesktopState.NotInstalled,
    val label: String = DesktopStateMachine.label(DesktopState.NotInstalled),
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    val canInstall: Boolean = false,
    val screenshot: Bitmap? = null,
    /** e.g. `1280x720 PNG 41 KB → cache/cos-desktop/last.png`, or the screenshot error. */
    val screenshotInfo: String? = null,
)

/**
 * Drives the temporary Desktop debug section (state, Start/Stop, screenshot) and the
 * "Install desktop" confirm flow on the Computer screen. All work goes through the
 * process-wide [LocalDesktopBridge] (the same instance the chat tools use via
 * [DesktopBridges.bridge]); leaving the screen does not stop a running desktop.
 */
class DesktopPanelViewModel(app: Application) : AndroidViewModel(app) {
    val bridge: LocalDesktopBridge = LocalDesktopBridge.get(app)
    private val shot = MutableStateFlow<Pair<Bitmap?, String?>>(null to null)

    val panel: StateFlow<DesktopPanelState> =
        combine(bridge.state, bridge.installMessage, shot) { s, msg, (bmp, info) ->
            DesktopPanelState(
                state = s,
                label = DesktopStateMachine.label(s, msg),
                canStart = s == DesktopState.Stopped || s is DesktopState.Error,
                canStop = DesktopStateMachine.canStop(s),
                canInstall = DesktopStateMachine.canInstall(s),
                screenshot = bmp,
                screenshotInfo = info,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DesktopPanelState())

    /** Re-reads the install marker (rootfs may have been reinstalled meanwhile). */
    fun refresh() = viewModelScope.launch(Dispatchers.IO) { bridge.refresh() }

    /** Size / metered / free-space prompt; computed before any byte is downloaded. */
    suspend fun installPrompt(withFirefox: Boolean): DesktopInstallPolicy.Prompt = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        DesktopInstallPolicy.prompt(
            arch = bridge.arch(),
            withFirefox = withFirefox,
            network = DesktopInstallPolicy.readNetwork(app),
            usableBytes = app.filesDir.usableSpace,
        )
    }

    fun install(withFirefox: Boolean) = viewModelScope.launch { bridge.install(withFirefox) }

    fun start() = viewModelScope.launch { bridge.start() }

    fun stop() = viewModelScope.launch { bridge.stop() }

    /** Real frame from Xvfb's `-fbdir` file via [DesktopBridge.screenshot] (PNG, scale 1). */
    fun screenshot() = viewModelScope.launch {
        val result = runCatching {
            val png = bridge.screenshot(ImageFormat.PNG, 1f)
            withContext(Dispatchers.IO) {
                val out = File(getApplication<Application>().cacheDir, "cos-desktop/last.png")
                out.parentFile?.mkdirs()
                out.writeBytes(png)
                val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                val info = "${bmp?.width}x${bmp?.height} PNG ${png.size / 1024} KB → cache/cos-desktop/last.png"
                Log.i(LocalComputerRuntime.LOG_TAG, "desktop screenshot ${bmp?.width}x${bmp?.height} png_bytes=${png.size}")
                bmp to info
            }
        }
        shot.value = result.getOrElse {
            Log.w(LocalComputerRuntime.LOG_TAG, "desktop screenshot failed: ${it.message}")
            null to "Screenshot failed: ${it.message}"
        }
    }
}
