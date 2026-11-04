package com.druk.lmplayground.computer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.theme.PlaygroundTheme

class ComputerFragment : Fragment() {

    private val viewModel: ComputerViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
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
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
