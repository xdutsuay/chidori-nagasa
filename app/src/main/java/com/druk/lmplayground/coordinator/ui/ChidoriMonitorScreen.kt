package com.druk.lmplayground.coordinator.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 */
@Composable
fun ChidoriMonitorContent(
    displayName: String,
    status: CoordinatorStatus?,
    runs: List<AgentRunSummary>,
    selectedRunDetail: AgentRunDetail?,
    showBackHeader: Boolean,
    onRunClick: (AgentRunSummary) -> Unit,
    onDismissRunDetail: () -> Unit,
    onOpenChatClick: () -> Unit,
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
                Text(displayName, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
        StatusCard(status)
        Spacer(Modifier.height(8.dp))
        // Remote chat entry (PRD §6.4) — a distinct surface, not the
        // on-device conversation screen; see ChidoriChatContent.
        Button(onClick = onOpenChatClick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chidori_chat_open))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.chidori_runs_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (runs.isEmpty()) {
            Text(
                text = stringResource(R.string.chidori_no_runs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(runs, key = { it.runId }) { run ->
                    RunRow(run = run, onClick = { onRunClick(run) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    selectedRunDetail?.let { detail ->
        RunDetailDialog(detail = detail, onDismiss = onDismissRunDetail)
    }
}

@Composable
private fun StatusCard(status: CoordinatorStatus?) {
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
                }
                DotIndicator(color)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = when (status) {
                    null -> stringResource(R.string.chidori_status_loading)
                    CoordinatorStatus.IDLE -> stringResource(R.string.chidori_status_idle)
                    CoordinatorStatus.RUNNING -> stringResource(R.string.chidori_status_running)
                    CoordinatorStatus.ERROR -> stringResource(R.string.chidori_status_error)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
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
            .then(Modifier.background(color))
    )
}

@Composable
private fun RunRow(run: AgentRunSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                Text(
                    text = when (run.state) {
                        AgentRunState.RUNNING -> stringResource(R.string.chidori_run_running)
                        AgentRunState.COMPLETED -> stringResource(R.string.chidori_run_completed)
                        AgentRunState.FAILED -> stringResource(R.string.chidori_run_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) { Text(stringResource(R.string.chidori_view_run)) }
        }
    }
}

@Composable
private fun RunDetailDialog(detail: AgentRunDetail, onDismiss: () -> Unit) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
