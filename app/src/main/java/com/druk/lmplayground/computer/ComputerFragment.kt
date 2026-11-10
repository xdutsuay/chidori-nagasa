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
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.druk.lmplayground.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val RESOURCE_SAMPLE_MS = 2_000L

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
                var resources by remember { mutableStateOf(CosResourceSampler.Sample(null, null)) }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(state.enabled, lifecycle) {
                    if (!state.enabled) {
                        resources = CosResourceSampler.Sample(null, null)
                        return@LaunchedEffect
                    }
                    val sampler = CosResourceSampler(
                        appPid = android.os.Process.myPid(),
                        packageName = requireContext().packageName,
                    )
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            resources = withContext(Dispatchers.IO) {
                                sampler.sample(SystemClock.elapsedRealtime())
                            }
                            delay(RESOURCE_SAMPLE_MS)
                        }
                    }
                }
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
                    headerStatus = when {
                        desktop.state is DesktopState.Running -> getString(R.string.cos_status_running)
                        state.status is ComputerStatus.Error -> getString(R.string.cos_status_error)
                        else -> ComputerScreenModel.statusLabel(state.status)
                    },
                    headerActive = desktop.state is DesktopState.Running,
                    headerAction = {
                        if (desktop.canStop) {
                            FilledTonalButton(
                                onClick = desktopViewModel::stop,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.padding(end = 2.dp),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(getString(R.string.cos_stop), modifier = Modifier.padding(start = 6.dp))
                            }
                        } else {
                            FilledTonalButton(
                                onClick = desktopViewModel::start,
                                enabled = desktop.canStart,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.padding(end = 2.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(getString(R.string.cos_desktop_start), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    },
                    resources = CosResourceStrip(
                        cpu = CosResourceFormat.cpu(resources.cpuPercent),
                        memory = CosResourceFormat.bytes(resources.rssBytes),
                        storage = CosResourceFormat.bytes(state.rootfsUsedBytes.takeIf { state.rootfsMode != null }),
                    ),
                    desktopPanel = {
                        val running = desktop.state as? DesktopState.Running
                        if (running != null) {
                            DesktopViewer(
                                bridge = desktopViewModel.bridge,
                                running = running,
                                onScreenshot = desktopViewModel::screenshot,
                            )
                        } else {
                            Column {
                                if (desktop.state is DesktopState.Installing || desktop.state is DesktopState.Error) DesktopDebugPanel(
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
                                Text(
                                    getString(R.string.cos_desktop_not_running),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    },
                    activityPanel = {
                        desktop.screenshotInfo?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium)
                        }
                        desktop.screenshot?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = getString(R.string.cos_desktop_screenshot_cd),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp)),
                            )
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
