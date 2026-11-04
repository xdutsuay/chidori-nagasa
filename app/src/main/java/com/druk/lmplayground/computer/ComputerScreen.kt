package com.druk.lmplayground.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerScreen(
    state: ComputerUiState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onEnsureRootfs: () -> Unit,
    onReinstallRootfs: () -> Unit = {},
    onSmoke: () -> Unit,
    onPrune: () -> Unit,
    onInstallRecipe: (String) -> Unit,
    onRotateSession: () -> Unit,
    onInputChange: (String) -> Unit,
    onHistoryPrev: () -> Unit,
    onHistoryNext: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onClearTerminal: () -> Unit,
) {
    val termScroll = rememberScrollState()
    LaunchedEffect(state.terminal) {
        termScroll.animateScrollTo(termScroll.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cos_computer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.cos_computer_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cos_enable),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                text = stringResource(R.string.cos_status, statusLabel(state.status)) +
                    " · " + stringResource(R.string.cos_session, state.sessionId.take(8)),
                style = MaterialTheme.typography.bodySmall,
            )
            CosRootfsStatusLine(
                mode = state.rootfsMode,
                usedBytes = state.rootfsUsedBytes,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEnsureRootfs,
                    enabled = state.enabled && !state.busy,
                ) { Text(stringResource(R.string.cos_ensure_rootfs)) }
                CosRootfsReinstallButton(
                    enabled = state.enabled && !state.busy,
                    onConfirmReinstall = onReinstallRootfs,
                )
                OutlinedButton(
                    onClick = onSmoke,
                    enabled = state.enabled && !state.busy,
                ) { Text(stringResource(R.string.cos_smoke_uname)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPrune, enabled = state.enabled) {
                    Text(stringResource(R.string.cos_prune))
                }
                OutlinedButton(onClick = onRotateSession) {
                    Text(stringResource(R.string.cos_rotate_session))
                }
                TextButton(onClick = onClearTerminal) {
                    Text(stringResource(R.string.cos_clear_terminal))
                }
            }

            Text(
                text = stringResource(R.string.cos_terminal_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = state.terminal.ifBlank { stringResource(R.string.cos_output_empty) },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(8.dp)
                    .verticalScroll(termScroll),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = CosCwd.promptLabel(state.cwd),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(
                    onClick = onHistoryPrev,
                    enabled = state.enabled && !state.busy,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Previous command",
                    )
                }
                IconButton(
                    onClick = onHistoryNext,
                    enabled = state.enabled && !state.busy,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Next command",
                    )
                }
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = state.enabled && !state.busy,
                    placeholder = { Text(stringResource(R.string.cos_terminal_hint)) },
                )
                if (state.busy) {
                    OutlinedButton(onClick = onStop) {
                        Text(stringResource(R.string.cos_stop))
                    }
                } else {
                    IconButton(
                        onClick = onRun,
                        enabled = state.enabled && state.input.isNotBlank(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.cos_run),
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.cos_recipes_title),
                style = MaterialTheme.typography.titleSmall,
            )
            state.recipes.forEach { recipe ->
                val installed = recipe.id in state.installedRecipes
                OutlinedButton(
                    onClick = { onInstallRecipe(recipe.id) },
                    enabled = state.enabled && !installed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (installed) {
                            stringResource(R.string.cos_recipe_installed, recipe.title)
                        } else {
                            stringResource(R.string.cos_recipe_install, recipe.title)
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun statusLabel(status: ComputerStatus): String = when (status) {
    ComputerStatus.Disabled -> "Disabled"
    ComputerStatus.MissingRootfs -> "Missing rootfs"
    ComputerStatus.Ready -> "Ready"
    ComputerStatus.Running -> "Running"
    is ComputerStatus.Error -> "Error: ${status.message}"
}
