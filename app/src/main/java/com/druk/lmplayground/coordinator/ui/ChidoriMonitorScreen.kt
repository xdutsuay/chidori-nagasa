package com.druk.lmplayground.coordinator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.coordinator.model.AgentMode
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import java.text.DateFormat
import java.util.Date

/**
 * Coordinator status + run list for one paired instance (PRD.md §6.3).
 * Read-only — protocol §2.4's v1 scope has no run-triggering or control
 * actions from the phone.
 *
 * Rendered *in place* inside the Chidori Desktop screen (see ChidoriScreen /
 * ChidoriDetailContent), not as its own nav destination — that keeps it
 * working identically on the phone Fragment/nav path and the tablet two-pane
 * detail pane (which has no NavController of its own). [showBackHeader] draws
 * an in-content back row for surfaces whose surrounding app bar can't be made
 * instance-aware (the tablet detail pane, whose TopAppBar title is owned by
 * SettingsScreen); the phone path drives back through its own TopAppBar and
 * passes false.
 *
 * Polls via [ChidoriViewModel]; see that class for why (no WS push for
 * status/runs in WIRE_CONTRACT.md v1).
 *
 * KMA-129 adds remote stop + inject on the run detail surface.
 */
@Composable
fun ChidoriMonitorContent(
    displayName: String,
    status: CoordinatorStatus?,
    statusMessage: String? = null,
    runs: List<AgentRunSummary>,
    runningSteps: Map<String, String>,
    lastUpdatedEpochMillis: Long?,
    selectedRunDetail: AgentRunDetail?,
    runInjectInput: String = "",
    runControlInProgress: Boolean = false,
    nodeOffering: Boolean,
    nodeOfferSupported: Boolean,
    showBackHeader: Boolean,
    onRunClick: (AgentRunSummary) -> Unit,
    onDismissRunDetail: () -> Unit,
    onRunInjectInputChanged: (String) -> Unit = {},
    onStopRun: () -> Unit = {},
    onInjectRunMessage: () -> Unit = {},
    onOpenChatClick: () -> Unit,
    onNodeOfferingChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        if (showBackHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenChatClick) {
                    Text(stringResource(R.string.chidori_chat_action))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (status == CoordinatorStatus.DISCONNECTED) {
            MonitorDisconnectBanner(
                displayName = displayName,
                detailMessage = statusMessage,
            )
            Spacer(Modifier.height(8.dp))
        }
        StatusCard(
            status = status,
            statusMessage = statusMessage,
            lastUpdatedEpochMillis = lastUpdatedEpochMillis,
        )
        Spacer(Modifier.height(8.dp))
        if (nodeOfferSupported) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chidori_node_offer_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.chidori_node_offer_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = nodeOffering,
                        onCheckedChange = onNodeOfferingChange,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = stringResource(R.string.chidori_runs_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (runs.isEmpty()) {
            Text(
                text = stringResource(R.string.chidori_watching_runs, displayName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(runs, key = { it.runId }) { run ->
                    RunRow(
                        run = run,
                        currentStep = runningSteps[run.runId] ?: run.currentStep,
                        onClick = { onRunClick(run) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    selectedRunDetail?.let { detail ->
        RunDetailDialog(
            detail = detail,
            injectInput = runInjectInput,
            controlInProgress = runControlInProgress,
            onInjectInputChanged = onRunInjectInputChanged,
            onStop = onStopRun,
            onInject = onInjectRunMessage,
            onDismiss = onDismissRunDetail,
        )
    }
}

@Composable
private fun StatusCard(
    status: CoordinatorStatus?,
    statusMessage: String?,
    lastUpdatedEpochMillis: Long?,
) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == null) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp))
            } else {
                val color = when (status) {
                    CoordinatorStatus.IDLE -> MaterialTheme.colorScheme.outline
                    CoordinatorStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    CoordinatorStatus.ERROR -> MaterialTheme.colorScheme.error
                    CoordinatorStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                }
                DotIndicator(color)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (status) {
                        null -> stringResource(R.string.chidori_status_loading)
                        CoordinatorStatus.IDLE -> stringResource(R.string.chidori_status_idle)
                        CoordinatorStatus.RUNNING -> stringResource(R.string.chidori_status_running)
                        CoordinatorStatus.ERROR -> stringResource(R.string.chidori_status_error)
                        CoordinatorStatus.DISCONNECTED ->
                            stringResource(R.string.chidori_status_disconnected)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!statusMessage.isNullOrBlank() && status != CoordinatorStatus.DISCONNECTED) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (lastUpdatedEpochMillis != null && status != CoordinatorStatus.DISCONNECTED) {
                    Text(
                        text = stringResource(
                            R.string.chidori_monitor_updated,
                            timeFormat.format(Date(lastUpdatedEpochMillis)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DotIndicator(color: Color) {
    Spacer(
        modifier = Modifier
            .height(12.dp)
            .width(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun MonitorDisconnectBanner(displayName: String, detailMessage: String?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.chidori_monitor_disconnected_banner, displayName),
                style = MaterialTheme.typography.labelMedium,
            )
            if (!detailMessage.isNullOrBlank()) {
                Text(
                    text = detailMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun RunRow(run: AgentRunSummary, currentStep: String?, onClick: () -> Unit) {
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val isRunning = run.state == AgentRunState.RUNNING
    val cardColors = if (isRunning) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        onClick = onClick,
        colors = cardColors,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isRunning) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CardDefaults.shape,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (run.mode) {
                        AgentMode.ASK -> stringResource(R.string.chidori_mode_ask)
                        AgentMode.AGENT -> stringResource(R.string.chidori_mode_agent)
                        AgentMode.PLAN -> stringResource(R.string.chidori_mode_plan)
                        AgentMode.DEBUG -> stringResource(R.string.chidori_mode_debug)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                val stateLabel = when (run.state) {
                    AgentRunState.RUNNING -> stringResource(R.string.chidori_run_running)
                    AgentRunState.COMPLETED -> stringResource(R.string.chidori_run_completed)
                    AgentRunState.FAILED -> stringResource(R.string.chidori_run_failed)
                }
                val started = if (run.startedAtEpochMillis > 0L) {
                    timeFormat.format(Date(run.startedAtEpochMillis))
                } else {
                    null
                }
                Text(
                    text = if (started != null) "$stateLabel · $started" else stateLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!currentStep.isNullOrBlank()) {
                    Text(
                        text = currentStep,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            TextButton(onClick = onClick) { Text(stringResource(R.string.chidori_view_run)) }
        }
    }
}

@Composable
private fun RunDetailDialog(
    detail: AgentRunDetail,
    injectInput: String,
    controlInProgress: Boolean,
    onInjectInputChanged: (String) -> Unit,
    onStop: () -> Unit,
    onInject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isRunning = detail.summary.state == AgentRunState.RUNNING
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.currentStep ?: stringResource(R.string.chidori_run_detail_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (detail.logTail.isEmpty()) {
                    Text(stringResource(R.string.chidori_no_log))
                } else {
                    detail.logTail.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = injectInput,
                    onValueChange = onInjectInputChanged,
                    enabled = !controlInProgress,
                    label = { Text(stringResource(R.string.chidori_run_inject_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onInject,
                enabled = !controlInProgress && injectInput.isNotBlank(),
            ) {
                Text(stringResource(R.string.chidori_run_inject_send))
            }
        },
        dismissButton = {
            Row {
                if (isRunning) {
                    TextButton(
                        onClick = onStop,
                        enabled = !controlInProgress,
                    ) {
                        Text(stringResource(R.string.chidori_run_stop))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        },
    )
}
