package com.druk.lmplayground.coordinator.ui

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

/**
 * Settings -> Chidori Desktop destination (nav_chidori). See
 * ChidoriViewModel and coordinator/README.md for scope/status — v1
 * client-mode pairing UI only.
 */
class ChidoriFragment : Fragment() {

    private val viewModel: ChidoriViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val discovered by viewModel.discoveredInstances.collectAsStateWithLifecycle()
                val paired by viewModel.pairedInstances.collectAsStateWithLifecycle()
                val manualHost by viewModel.manualHost.collectAsStateWithLifecycle()
                val manualPort by viewModel.manualPort.collectAsStateWithLifecycle()
                val pairingInProgressFor by viewModel.pairingInProgressFor.collectAsStateWithLifecycle()
                val lastPairingError by viewModel.lastPairingError.collectAsStateWithLifecycle()

                ChidoriScreen(
                    discoveredInstances = discovered,
                    pairedInstances = paired,
                    pairingInProgressFor = pairingInProgressFor,
                    lastPairingError = lastPairingError,
                    manualHost = manualHost,
                    manualPort = manualPort,
                    onManualHostChanged = viewModel::onManualHostChanged,
                    onManualPortChanged = viewModel::onManualPortChanged,
                    onBeginPairing = viewModel::beginPairing,
                    onConfirmPairingCode = viewModel::confirmPairingCode,
                    onUnpair = viewModel::unpair,
                    onDismissError = viewModel::dismissError,
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
