package com.xdutsuay.chidori.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.xdutsuay.chidori.computer.ComputerScreen
import com.xdutsuay.chidori.computer.ComputerStatus
import com.xdutsuay.chidori.computer.ComputerUiState
import com.xdutsuay.chidori.theme.PlaygroundTheme
import org.junit.Rule
import org.junit.Test

/**
 * Layout evidence for the terminal-first Computer screen (CP1 UI pass). Excluded from
 * `-PskipScreenshots` runs like the other Paparazzi tests; record with
 * `recordPaparazziDebug --tests '*ComputerScreenSnapshots*'`.
 */
class ComputerScreenSnapshots {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
    )

    private val terminal = buildString {
        append("cos:/ $ uname -a\n")
        append("Linux localhost 5.15.123-android13 #1 SMP PREEMPT aarch64 Linux\n[exit 0]\n")
        append("cos:/ $ cat /etc/os-release\n")
        append("NAME=\"Alpine Linux\"\nID=alpine\nVERSION_ID=3.24.3\n")
        append("PRETTY_NAME=\"Alpine Linux v3.24\"\n[exit 0]\n")
        repeat(30) { append("line $it of a long listing that should scroll\n") }
        append("cos:/root $ ")
    }

    private fun state() = ComputerUiState(
        enabled = true,
        status = ComputerStatus.Ready,
        sessionId = "3f9b9049ca77",
        terminal = terminal,
        input = "apk add python3",
        cwd = "root",
        rootfsMode = "alpine-tar",
        rootfsUsedBytes = 9_437_184L,
    )

    @Test
    fun computerScreen_dark() = shot(dark = true)

    @Test
    fun computerScreen_light() = shot(dark = false)

    @Test
    fun computerScreen_empty() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true, isDynamicColor = false) {
                render(ComputerUiState(enabled = true, status = ComputerStatus.MissingRootfs))
            }
        }
    }

    private fun shot(dark: Boolean) {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = dark, isDynamicColor = false) { render(state()) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun render(s: ComputerUiState) {
        ComputerScreen(
            state = s,
            onBack = {},
            onEnabledChange = {},
            onEnsureRootfs = {},
            onSmoke = {},
            onPrune = {},
            onInstallRecipe = {},
            onRotateSession = {},
            onInputChange = {},
            onHistoryPrev = {},
            onHistoryNext = {},
            onRun = {},
            onStop = {},
            onClearTerminal = {},
        )
    }
}
