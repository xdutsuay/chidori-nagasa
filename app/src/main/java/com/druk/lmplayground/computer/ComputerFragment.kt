package com.druk.lmplayground.computer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.computer.desktop.DesktopDebugPanel
import com.druk.lmplayground.computer.desktop.DesktopInstallDialog
import com.druk.lmplayground.computer.desktop.DesktopInstallPolicy
import com.druk.lmplayground.computer.desktop.DesktopPanelViewModel
import com.druk.lmplayground.computer.desktop.DesktopState
import com.druk.lmplayground.computer.desktop.DesktopStateMachine
import com.druk.lmplayground.computer.desktop.DesktopViewer
import kotlinx.coroutines.launch
import com.druk.lmplayground.theme.PlaygroundTheme
import androidx.compose.foundation.layout.Column

class ComputerFragment : Fragment() {

    private val viewModel: ComputerViewModel by viewModels()
    private val desktopViewModel: DesktopPanelViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val desktop by desktopViewModel.panel.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                var installPrompt by remember { mutableStateOf<DesktopInstallPolicy.Prompt?>(null) }
                var withFirefox by remember { mutableStateOf(false) }
                fun showPrompt(firefox: Boolean) {
                    withFirefox = firefox
                    scope.launch { installPrompt = desktopViewModel.installPrompt(firefox) }
                }
                ComputerScreen(
                    state = state,
                    onBack = { findNavController().popBackStack() },
                    onEnabledChange = viewModel::setEnabled,
                    onEnsureRootfs = viewModel::ensureRootfs,
                    onReinstallRootfs = viewModel::reinstallRootfs,
                    onSmoke = viewModel::smokeExec,
                    onPrune = viewModel::prune,
                    onInstallRecipe = viewModel::installRecipe,
                    onRotateSession = viewModel::rotateSession,
                    onInputChange = viewModel::onInputChange,
                    onHistoryPrev = viewModel::onHistoryPrev,
                    onHistoryNext = viewModel::onHistoryNext,
                    onRun = viewModel::runInput,
                    onStop = viewModel::stopExec,
                    onClearTerminal = viewModel::clearTerminal,
                    installDesktopLabel = DesktopInstallPolicy.menuLabel(desktopViewModel.bridge.arch()),
                    desktopIdle = DesktopStateMachine.canInstall(desktop.state),
                    onInstallDesktop = { showPrompt(false) },
                    desktopPanel = {
                        Column {
                            DesktopDebugPanel(
                                panel = desktop,
                                enabled = state.enabled,
                                onStart = desktopViewModel::start,
                                onStop = desktopViewModel::stop,
                                onScreenshot = desktopViewModel::screenshot,
                                onRetry = {
                                    // Install failure leaves marker unset → re-prompt with size.
                                    // Start failure with desktop installed → Start again.
                                    if (desktopViewModel.bridge.isInstalled()) desktopViewModel.start()
                                    else showPrompt(false)
                                },
                            )
                            val running = desktop.state as? DesktopState.Running
                            if (running != null) {
                                DesktopViewer(
                                    bridge = desktopViewModel.bridge,
                                    running = running,
                                )
                            }
                        }
                    },
                )
                installPrompt?.let { p ->
                    DesktopInstallDialog(
                        prompt = p,
                        withFirefox = withFirefox,
                        onWithFirefoxChange = { showPrompt(it) },
                        onConfirm = {
                            installPrompt = null
                            desktopViewModel.install(withFirefox)
                        },
                        onDismiss = { installPrompt = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        desktopViewModel.refresh()
    }
}
