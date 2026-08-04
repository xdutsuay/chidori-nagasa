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
                val pairingCodeEntryFor by viewModel.pairingCodeEntryFor.collectAsStateWithLifecycle()
                val lastPairingError by viewModel.lastPairingError.collectAsStateWithLifecycle()
                val monitoredInstance by viewModel.monitoredInstance.collectAsStateWithLifecycle()
                val monitorStatus by viewModel.monitorStatus.collectAsStateWithLifecycle()
                val monitorRuns by viewModel.monitorRuns.collectAsStateWithLifecycle()
                val monitorRunDetail by viewModel.monitorRunDetail.collectAsStateWithLifecycle()
                val chatOpen by viewModel.chatOpen.collectAsStateWithLifecycle()
                val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
                val chatConnectionState by viewModel.chatConnectionState.collectAsStateWithLifecycle()
                val chatInput by viewModel.chatInput.collectAsStateWithLifecycle()
                val nodeOffering by viewModel.nodeOffering.collectAsStateWithLifecycle()
                val nodeOfferError by viewModel.nodeOfferError.collectAsStateWithLifecycle()

                ChidoriScreen(
                    discoveredInstances = discovered,
                    pairedInstances = paired,
                    pairingInProgressFor = pairingInProgressFor,
                    pairingCodeEntryFor = pairingCodeEntryFor,
                    lastPairingError = lastPairingError,
                    manualHost = manualHost,
                    manualPort = manualPort,
                    monitoredInstance = monitoredInstance,
                    monitorStatus = monitorStatus,
                    monitorRuns = monitorRuns,
                    monitorRunDetail = monitorRunDetail,
                    chatOpen = chatOpen,
                    chatMessages = chatMessages,
                    chatConnectionState = chatConnectionState,
                    chatInput = chatInput,
                    onManualHostChanged = viewModel::onManualHostChanged,
                    onManualPortChanged = viewModel::onManualPortChanged,
                    onBeginPairing = viewModel::beginPairing,
                    onBeginManualPairing = viewModel::beginManualPairing,
                    onConfirmPairingCode = viewModel::confirmPairingCode,
                    onDismissPairingCodeEntry = viewModel::dismissPairingCodeEntry,
                    onUnpair = viewModel::unpair,
                    onPairedInstanceClick = viewModel::openMonitor,
                    onCloseMonitor = viewModel::closeMonitor,
                    onRunClick = { viewModel.openRunDetail(it.runId) },
                    onDismissRunDetail = viewModel::dismissRunDetail,
                    onOpenChat = viewModel::openChat,
                    onCloseChat = viewModel::closeChat,
                    onChatInputChanged = viewModel::onChatInputChanged,
                    onSendChat = viewModel::sendChatMessage,
                    nodeOffering = nodeOffering,
                    nodeOfferSupported = true,
                    onNodeOfferingChange = viewModel::setNodeOffering,
                    nodeOfferError = nodeOfferError,
                    onDismissNodeOfferError = viewModel::dismissNodeOfferError,
                    onDismissError = viewModel::dismissError,
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
