package com.druk.lmplayground.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.computer.ComputerScreenModel.Action

/**
 * Terminal-first Computer screen: top bar (title, enable switch, ⋮ menu holding every
 * action), one ellipsized status line, a full-height auto-scrolling monospace terminal,
 * and an IME-aware command row pinned to the bottom.
 */
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.cos_computer_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = onEnabledChange,
                        modifier = Modifier.testTag(TAG_ENABLE),
                    )
                    ComputerOverflowMenu(
                        state = state,
                        onEnsureRootfs = onEnsureRootfs,
                        onReinstallRootfs = onReinstallRootfs,
                        onSmoke = onSmoke,
                        onPrune = onPrune,
                        onInstallRecipe = onInstallRecipe,
                        onRotateSession = onRotateSession,
                        onClearTerminal = onClearTerminal,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Text(
                text = ComputerScreenModel.statusLine(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .testTag(TAG_STATUS),
            )
            CosTerminalOutput(
                text = state.terminal,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            CosCommandRow(
                state = state,
                onInputChange = onInputChange,
                onHistoryPrev = onHistoryPrev,
                onHistoryNext = onHistoryNext,
                onRun = onRun,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun ComputerOverflowMenu(
    state: ComputerUiState,
    onEnsureRootfs: () -> Unit,
    onReinstallRootfs: () -> Unit,
    onSmoke: () -> Unit,
    onPrune: () -> Unit,
    onInstallRecipe: (String) -> Unit,
    onRotateSession: () -> Unit,
    onClearTerminal: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Two-step reinstall: first tap arms (menu stays open), second tap confirms.
    var reinstallArmed by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TAG_MENU)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cos_more_actions))
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = {
                open = false
                reinstallArmed = false
            },
        ) {
            fun close() {
                open = false
                reinstallArmed = false
            }
            ComputerScreenModel.menuItems(state).forEach { item ->
                val label = when (item.action) {
                    Action.INSTALL_ROOTFS -> stringResource(R.string.cos_ensure_rootfs)
                    Action.REINSTALL_ROOTFS -> if (reinstallArmed) {
                        stringResource(R.string.cos_reinstall_rootfs_confirm)
                    } else {
                        stringResource(R.string.cos_reinstall_rootfs)
                    }
                    Action.SMOKE -> stringResource(R.string.cos_smoke_uname)
                    Action.PRUNE -> stringResource(R.string.cos_prune)
                    Action.NEW_SESSION -> stringResource(R.string.cos_rotate_session)
                    Action.CLEAR_TERMINAL -> stringResource(R.string.cos_clear_terminal)
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (item.action == Action.REINSTALL_ROOTFS && reinstallArmed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color.Unspecified
                            },
                        )
                    },
                    enabled = item.enabled,
                    modifier = Modifier.testTag(menuTag(item.action)),
                    onClick = {
                        when (item.action) {
                            Action.INSTALL_ROOTFS -> { close(); onEnsureRootfs() }
                            Action.REINSTALL_ROOTFS -> if (reinstallArmed) {
                                close(); onReinstallRootfs()
                            } else {
                                reinstallArmed = true
                            }
                            Action.SMOKE -> { close(); onSmoke() }
                            Action.PRUNE -> { close(); onPrune() }
                            Action.NEW_SESSION -> { close(); onRotateSession() }
                            Action.CLEAR_TERMINAL -> { close(); onClearTerminal() }
                        }
                    },
                )
            }
            val recipes = ComputerScreenModel.recipeItems(state)
            if (recipes.isNotEmpty()) {
                HorizontalDivider()
                recipes.forEach { r ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (r.installed) {
                                    stringResource(R.string.cos_recipe_installed, r.title)
                                } else {
                                    stringResource(R.string.cos_recipe_install, r.title)
                                },
                            )
                        },
                        enabled = r.enabled,
                        onClick = { close(); onInstallRecipe(r.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CosTerminalOutput(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // Keep the newest output visible.
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Text(
            text = text.ifBlank { stringResource(R.string.cos_output_empty) },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag(TAG_TERMINAL),
        )
    }
}

@Composable
private fun CosCommandRow(
    state: ComputerUiState,
    onInputChange: (String) -> Unit,
    onHistoryPrev: () -> Unit,
    onHistoryNext: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val idle = state.enabled && !state.busy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .testTag(TAG_INPUT),
            singleLine = true,
            enabled = idle,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            prefix = {
                Text(
                    CosCwd.promptLabel(state.cwd) + " ",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            },
            placeholder = {
                Text(
                    stringResource(R.string.cos_terminal_hint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(
                onSend = { if (ComputerScreenModel.canRun(state)) onRun() },
            ),
        )
        IconButton(onClick = onHistoryPrev, enabled = idle) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.cos_history_prev))
        }
        IconButton(onClick = onHistoryNext, enabled = idle) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.cos_history_next))
        }
        if (state.busy) {
            IconButton(onClick = onStop, modifier = Modifier.testTag(TAG_STOP)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cos_stop))
            }
        } else {
            IconButton(
                onClick = onRun,
                enabled = ComputerScreenModel.canRun(state),
                modifier = Modifier.testTag(TAG_SEND),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cos_run))
            }
        }
    }
}

internal const val TAG_ENABLE = "cos_enable"
internal const val TAG_MENU = "cos_menu"
internal const val TAG_STATUS = "cos_status"
internal const val TAG_TERMINAL = "cos_terminal"
internal const val TAG_INPUT = "cos_input"
internal const val TAG_SEND = "cos_send"
internal const val TAG_STOP = "cos_stop"
internal fun menuTag(action: Action): String = "cos_menu_" + action.name.lowercase()
