@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.coordinator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.druk.lmplayground.R
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState
import com.druk.lmplayground.coordinator.model.RemoteChatMessage

/**
 * Settings -> Chidori Desktop. v1 client-mode scope (PRD.md §6.2/§6.3/§6.4):
 * discover + pair with a chidori/lclreason desktop instance on the LAN (or
 * enter host:port manually), then monitor its coordinator and chat through
 * its attached model. All three surfaces render in place; see
 * ChidoriMonitorScreen's kdoc for why (tablet detail pane has no
 * NavController). Read/monitor + chat only — no run-triggering (protocol
 * §2.4).
 */
@Composable
fun ChidoriScreen(
    discoveredInstances: List<DiscoveredInstance>,
    pairedInstances: List<PairedInstance>,
    pairingInProgressFor: InstanceId?,
    pairingCodeEntryFor: InstanceId?,
    lastPairingError: String?,
    manualHost: String,
    manualPort: String,
    monitoredInstance: PairedInstance?,
    monitorStatus: CoordinatorStatus?,
    monitorStatusMessage: String? = null,
    monitorRuns: List<AgentRunSummary>,
    monitorRunningSteps: Map<String, String> = emptyMap(),
    monitorLastUpdatedEpochMillis: Long? = null,
    monitorRunDetail: AgentRunDetail?,
    chatOpen: Boolean,
    chatMessages: List<RemoteChatMessage>,
    chatConnectionState: CoordinatorConnectionState,
    chatInput: String,
    chatAwaitingReply: Boolean = false,
    onManualHostChanged: (String) -> Unit,
    onManualPortChanged: (String) -> Unit,
    onBeginPairing: (DiscoveredInstance) -> Unit,
    onBeginManualPairing: () -> Unit,
    onConfirmPairingCode: (InstanceId, String) -> Unit,
    onDismissPairingCodeEntry: () -> Unit,
    onUnpair: (InstanceId) -> Unit,
    onPairedInstanceClick: (PairedInstance) -> Unit,
    onCloseMonitor: () -> Unit,
    onRunClick: (AgentRunSummary) -> Unit,
    onDismissRunDetail: () -> Unit,
    onOpenChat: () -> Unit,
    onCloseChat: () -> Unit,
    onChatInputChanged: (String) -> Unit,
    onSendChat: () -> Unit,
    nodeOffering: Boolean = false,
    nodeOfferSupported: Boolean = false,
    onNodeOfferingChange: (Boolean) -> Unit = {},
    nodeOfferError: String? = null,
    onDismissNodeOfferError: () -> Unit = {},
    onDismissError: () -> Unit,
    onBackClick: () -> Unit,
) {
    // Back peels one layer at a time: chat -> monitor -> leave the screen.
    BackHandler(enabled = chatOpen, onBack = onCloseChat)
    BackHandler(enabled = monitoredInstance != null && !chatOpen, onBack = onCloseMonitor)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(monitoredInstance?.displayName ?: stringResource(R.string.chidori_desktop)) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            chatOpen -> onCloseChat()
                            monitoredInstance != null -> onCloseMonitor()
                            else -> onBackClick()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (monitoredInstance != null && !chatOpen) {
                        TextButton(onClick = onOpenChat) {
                            Text(stringResource(R.string.chidori_chat_action))
                        }
                    }
                },
            )
        }
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when {
            monitoredInstance != null && chatOpen -> ChidoriChatContent(
                displayName = monitoredInstance.displayName,
                messages = chatMessages,
                connectionState = chatConnectionState,
                input = chatInput,
                awaitingReply = chatAwaitingReply,
                showBackHeader = false,
                onInputChanged = onChatInputChanged,
                onSendClick = onSendChat,
                onClose = onCloseChat,
                modifier = contentModifier,
            )
            monitoredInstance != null -> ChidoriMonitorContent(
                displayName = monitoredInstance.displayName,
                status = monitorStatus,
                statusMessage = monitorStatusMessage,
                runs = monitorRuns,
                runningSteps = monitorRunningSteps,
                lastUpdatedEpochMillis = monitorLastUpdatedEpochMillis,
                selectedRunDetail = monitorRunDetail,
                nodeOffering = nodeOffering,
                nodeOfferSupported = nodeOfferSupported,
                showBackHeader = false,
                onRunClick = onRunClick,
                onDismissRunDetail = onDismissRunDetail,
                onOpenChatClick = onOpenChat,
                onNodeOfferingChange = onNodeOfferingChange,
                onClose = onCloseMonitor,
                modifier = contentModifier,
            )
            else -> ChidoriBody(
                discoveredInstances = discoveredInstances,
                pairedInstances = pairedInstances,
                pairingInProgressFor = pairingInProgressFor,
                pairingCodeEntryFor = pairingCodeEntryFor,
                lastPairingError = lastPairingError,
                manualHost = manualHost,
                manualPort = manualPort,
                onManualHostChanged = onManualHostChanged,
                onManualPortChanged = onManualPortChanged,
                onBeginPairing = onBeginPairing,
                onBeginManualPairing = onBeginManualPairing,
                onConfirmPairingCode = onConfirmPairingCode,
                onDismissPairingCodeEntry = onDismissPairingCodeEntry,
                onUnpair = onUnpair,
                onPairedInstanceClick = onPairedInstanceClick,
                onDismissError = onDismissError,
                modifier = contentModifier,
            )
        }
    }
    nodeOfferError?.let { err ->
        AlertDialog(
            onDismissRequest = onDismissNodeOfferError,
            title = { Text(stringResource(R.string.chidori_node_offer_title)) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = onDismissNodeOfferError) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/**
 * Self-contained tablet detail-pane variant — owns its own [ChidoriViewModel]
 * (via the Compose `viewModel()` helper) the same way `FaqContent()` /
 * `PrivacyPolicyContent()` do, so `SettingsScreen`'s two-pane layout doesn't
 * need a `chidoriDetailContent` lambda threaded in from the Fragment.
 */
@Composable
fun ChidoriDetailContent(modifier: Modifier = Modifier) {
    val viewModel: ChidoriViewModel = viewModel()
    val discovered by viewModel.discoveredInstances.collectAsStateWithLifecycle()
    val paired by viewModel.pairedInstances.collectAsStateWithLifecycle()
    val manualHost by viewModel.manualHost.collectAsStateWithLifecycle()
    val manualPort by viewModel.manualPort.collectAsStateWithLifecycle()
    val pairingInProgressFor by viewModel.pairingInProgressFor.collectAsStateWithLifecycle()
    val pairingCodeEntryFor by viewModel.pairingCodeEntryFor.collectAsStateWithLifecycle()
    val lastPairingError by viewModel.lastPairingError.collectAsStateWithLifecycle()
    val monitoredInstance by viewModel.monitoredInstance.collectAsStateWithLifecycle()
    val monitorStatus by viewModel.monitorStatus.collectAsStateWithLifecycle()
    val monitorStatusMessage by viewModel.monitorStatusMessage.collectAsStateWithLifecycle()
    val monitorRuns by viewModel.monitorRuns.collectAsStateWithLifecycle()
    val monitorRunningSteps by viewModel.monitorRunningSteps.collectAsStateWithLifecycle()
    val monitorLastUpdatedEpochMillis by viewModel.monitorLastUpdatedEpochMillis.collectAsStateWithLifecycle()
    val monitorRunDetail by viewModel.monitorRunDetail.collectAsStateWithLifecycle()
    val chatOpen by viewModel.chatOpen.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val chatConnectionState by viewModel.chatConnectionState.collectAsStateWithLifecycle()
    val chatInput by viewModel.chatInput.collectAsStateWithLifecycle()
    val chatAwaitingReply by viewModel.chatAwaitingReply.collectAsStateWithLifecycle()
    val nodeOffering by viewModel.nodeOffering.collectAsStateWithLifecycle()
    val nodeOfferSupported by viewModel.nodeOfferSupported.collectAsStateWithLifecycle()
    val nodeOfferError by viewModel.nodeOfferError.collectAsStateWithLifecycle()

    BackHandler(enabled = chatOpen, onBack = viewModel::closeChat)
    BackHandler(enabled = monitoredInstance != null && !chatOpen, onBack = viewModel::closeMonitor)

    val monitored = monitoredInstance
    // The detail pane's TopAppBar title is owned by SettingsScreen and stays
    // "Chidori Desktop", so the monitor/chat surfaces carry their own back header.
    when {
        monitored != null && chatOpen -> ChidoriChatContent(
            displayName = monitored.displayName,
            messages = chatMessages,
            connectionState = chatConnectionState,
            input = chatInput,
            awaitingReply = chatAwaitingReply,
            showBackHeader = true,
            onInputChanged = viewModel::onChatInputChanged,
            onSendClick = viewModel::sendChatMessage,
            onClose = viewModel::closeChat,
            modifier = modifier.fillMaxSize(),
        )
        monitored != null -> ChidoriMonitorContent(
            displayName = monitored.displayName,
            status = monitorStatus,
            statusMessage = monitorStatusMessage,
            runs = monitorRuns,
            runningSteps = monitorRunningSteps,
            lastUpdatedEpochMillis = monitorLastUpdatedEpochMillis,
            selectedRunDetail = monitorRunDetail,
            nodeOffering = nodeOffering,
            nodeOfferSupported = nodeOfferSupported,
            showBackHeader = true,
            onRunClick = { viewModel.openRunDetail(it.runId) },
            onDismissRunDetail = viewModel::dismissRunDetail,
            onOpenChatClick = viewModel::openChat,
            onNodeOfferingChange = viewModel::setNodeOffering,
            onClose = viewModel::closeMonitor,
            modifier = modifier.fillMaxSize(),
        )
        else -> ChidoriBody(
            discoveredInstances = discovered,
            pairedInstances = paired,
            pairingInProgressFor = pairingInProgressFor,
            pairingCodeEntryFor = pairingCodeEntryFor,
            lastPairingError = lastPairingError,
            manualHost = manualHost,
            manualPort = manualPort,
            onManualHostChanged = viewModel::onManualHostChanged,
            onManualPortChanged = viewModel::onManualPortChanged,
            onBeginPairing = viewModel::beginPairing,
            onBeginManualPairing = viewModel::beginManualPairing,
            onConfirmPairingCode = viewModel::confirmPairingCode,
            onDismissPairingCodeEntry = viewModel::dismissPairingCodeEntry,
            onUnpair = viewModel::unpair,
            onPairedInstanceClick = viewModel::openMonitor,
            onDismissError = viewModel::dismissError,
            modifier = modifier.fillMaxSize(),
        )
    }
    nodeOfferError?.let { err ->
        AlertDialog(
            onDismissRequest = viewModel::dismissNodeOfferError,
            title = { Text(stringResource(R.string.chidori_node_offer_title)) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissNodeOfferError) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun ChidoriBody(
    discoveredInstances: List<DiscoveredInstance>,
    pairedInstances: List<PairedInstance>,
    pairingInProgressFor: InstanceId?,
    pairingCodeEntryFor: InstanceId?,
    lastPairingError: String?,
    manualHost: String,
    manualPort: String,
    onManualHostChanged: (String) -> Unit,
    onManualPortChanged: (String) -> Unit,
    onBeginPairing: (DiscoveredInstance) -> Unit,
    onBeginManualPairing: () -> Unit,
    onConfirmPairingCode: (InstanceId, String) -> Unit,
    onDismissPairingCodeEntry: () -> Unit,
    onUnpair: (InstanceId) -> Unit,
    onPairedInstanceClick: (PairedInstance) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.chidori_desktop_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

            if (pairedInstances.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chidori_paired_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                pairedInstances.forEach { paired ->
                    PairedInstanceRow(
                        paired = paired,
                        onClick = { onPairedInstanceClick(paired) },
                        onUnpair = { onUnpair(paired.instanceId) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                text = stringResource(R.string.chidori_discovered_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (discoveredInstances.isEmpty()) {
                Text(
                    text = stringResource(R.string.chidori_discovering),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                discoveredInstances
                    .filter { discovered -> pairedInstances.none { it.instanceId == discovered.instanceId } }
                    .forEach { instance ->
                        DiscoveredInstanceRow(
                            instance = instance,
                            isPairing = pairingInProgressFor == instance.instanceId,
                            onPairClick = { onBeginPairing(instance) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.chidori_manual_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chidori_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = onManualHostChanged,
                    label = { Text(stringResource(R.string.chidori_manual_host)) },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = manualPort,
                    onValueChange = onManualPortChanged,
                    label = { Text(stringResource(R.string.chidori_manual_port)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                // The code-entry dialog opens off pairingCodeEntryFor once
                // /pairing/begin actually succeeds — same as discovered
                // pairing — so a failed begin can't strand a dialog whose
                // codes can never match.
                onClick = onBeginManualPairing,
                enabled = manualHost.isNotBlank() && manualPort.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.chidori_pair))
            }
        }

    lastPairingError?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissError,
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(android.R.string.ok)) }
            },
            title = { Text(stringResource(R.string.chidori_pairing_failed_title)) },
            text = { Text(error) },
        )
    }

    pairingCodeEntryFor?.let { instanceId ->
        PairingCodeDialog(
            onConfirm = { code -> onConfirmPairingCode(instanceId, code) },
            onDismiss = onDismissPairingCodeEntry,
        )
    }
}

@Composable
private fun PairedInstanceRow(paired: PairedInstance, onClick: () -> Unit, onUnpair: () -> Unit) {
    // Tapping the row opens the coordinator monitor (PRD.md §6.3); the Unpair
    // button inside consumes its own clicks, so it still works independently.
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(paired.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = when (paired.pairingState) {
                            PairingState.PAIRED -> stringResource(R.string.chidori_status_paired)
                            PairingState.REQUIRES_REPAIR -> stringResource(R.string.chidori_status_requires_repair)
                            else -> stringResource(R.string.chidori_status_not_paired)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onUnpair) {
                Text(stringResource(R.string.chidori_unpair))
            }
        }
    }
}

@Composable
private fun DiscoveredInstanceRow(
    instance: DiscoveredInstance,
    isPairing: Boolean,
    onPairClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(instance.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${instance.host}:${instance.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onPairClick, enabled = !isPairing) {
                Text(
                    if (isPairing) {
                        stringResource(R.string.chidori_pairing_in_progress)
                    } else {
                        stringResource(R.string.chidori_pair)
                    }
                )
            }
        }
    }
}

@Composable
private fun PairingCodeDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
        title = { Text(stringResource(R.string.chidori_enter_code_title)) },
        text = {
            Column {
                Text(stringResource(R.string.chidori_enter_code_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.chidori_pairing_code)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(code) }, enabled = code.isNotBlank()) {
                Text(stringResource(R.string.chidori_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
