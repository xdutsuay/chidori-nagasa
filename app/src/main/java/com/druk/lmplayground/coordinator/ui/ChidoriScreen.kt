@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.coordinator.ui

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
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState

/**
 * Settings -> Chidori Desktop. v1 client-mode scope only (PRD.md §6.2/§6.3):
 * discover + pair with a chidori/lclreason desktop instance on the LAN, or
 * enter host:port manually. Coordinator status/run monitoring and remote
 * chat (PRD.md §6.3/§6.4) land in a later pass once CoordinatorApi's
 * status/runs/chat calls are wired to a UI consumer — see
 * coordinator/README.md and ROADMAP.md Phase 2/3.
 */
@Composable
fun ChidoriScreen(
    discoveredInstances: List<DiscoveredInstance>,
    pairedInstances: List<PairedInstance>,
    pairingInProgressFor: InstanceId?,
    lastPairingError: String?,
    manualHost: String,
    manualPort: String,
    onManualHostChanged: (String) -> Unit,
    onManualPortChanged: (String) -> Unit,
    onBeginPairing: (DiscoveredInstance) -> Unit,
    onConfirmPairingCode: (InstanceId, String) -> Unit,
    onUnpair: (InstanceId) -> Unit,
    onDismissError: () -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chidori_desktop)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ChidoriBody(
            discoveredInstances = discoveredInstances,
            pairedInstances = pairedInstances,
            pairingInProgressFor = pairingInProgressFor,
            lastPairingError = lastPairingError,
            manualHost = manualHost,
            manualPort = manualPort,
            onManualHostChanged = onManualHostChanged,
            onManualPortChanged = onManualPortChanged,
            onBeginPairing = onBeginPairing,
            onConfirmPairingCode = onConfirmPairingCode,
            onUnpair = onUnpair,
            onDismissError = onDismissError,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
    val lastPairingError by viewModel.lastPairingError.collectAsStateWithLifecycle()

    ChidoriBody(
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
        modifier = modifier.fillMaxSize(),
    )
}

@Composable
private fun ChidoriBody(
    discoveredInstances: List<DiscoveredInstance>,
    pairedInstances: List<PairedInstance>,
    pairingInProgressFor: InstanceId?,
    lastPairingError: String?,
    manualHost: String,
    manualPort: String,
    onManualHostChanged: (String) -> Unit,
    onManualPortChanged: (String) -> Unit,
    onBeginPairing: (DiscoveredInstance) -> Unit,
    onConfirmPairingCode: (InstanceId, String) -> Unit,
    onUnpair: (InstanceId) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pairingCodeDialogFor by remember { mutableStateOf<InstanceId?>(null) }

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
                    PairedInstanceRow(paired = paired, onUnpair = { onUnpair(paired.instanceId) })
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
                            onPairClick = {
                                onBeginPairing(instance)
                                pairingCodeDialogFor = instance.instanceId
                            },
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

    pairingCodeDialogFor?.let { instanceId ->
        PairingCodeDialog(
            onConfirm = { code ->
                onConfirmPairingCode(instanceId, code)
                pairingCodeDialogFor = null
            },
            onDismiss = { pairingCodeDialogFor = null },
        )
    }
}

@Composable
private fun PairedInstanceRow(paired: PairedInstance, onUnpair: () -> Unit) {
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
