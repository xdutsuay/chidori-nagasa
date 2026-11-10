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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    /** Label for the "Install desktop" ⋮ item (shows the download size). */
    installDesktopLabel: String = "Install desktop",
    desktopIdle: Boolean = true,
    onInstallDesktop: () -> Unit = {},
    /** Desktop tab content (controls + live viewer). */
    desktopPanel: (@Composable () -> Unit)? = null,
    /** Activity tab content; an empty-state message is shown when null. */
    activityPanel: (@Composable () -> Unit)? = null,
    /** Pill next to the title, e.g. "Running"; hidden when null. */
    headerStatus: String? = null,
    headerActive: Boolean = false,
    /** Primary desktop action in the top bar (Start / Stop). */
    headerAction: (@Composable () -> Unit)? = null,
    resources: CosResourceStrip? = null,
    onOtherComputers: (() -> Unit)? = null,
) {
    val cosSwitchCd = stringResource(R.string.cd_cos_desktop_switch)
    var tab by rememberSaveable { mutableStateOf(ComputerTab.DESKTOP) }
    CosFirstRunDialogHost()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(R.string.cos_header_title),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (headerStatus != null) StatusPill(headerStatus, headerActive)
                    }
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
                    if (state.enabled && headerAction != null) {
                        headerAction()
                    } else if (!state.enabled) {
                        Switch(
                            checked = false,
                            onCheckedChange = onEnabledChange,
                            modifier = Modifier
                                .testTag(TAG_ENABLE)
                                .semantics { contentDescription = cosSwitchCd },
                        )
                    }
                    ComputerOverflowMenu(
                        onOtherComputers = onOtherComputers,
                        onTurnOff = if (state.enabled) {
                            { onEnabledChange(false) }
                        } else {
                            null
                        },
                        state = state,
                        onEnsureRootfs = onEnsureRootfs,
                        onReinstallRootfs = onReinstallRootfs,
                        onSmoke = onSmoke,
                        onPrune = onPrune,
                        onInstallRecipe = onInstallRecipe,
                        onRotateSession = onRotateSession,
                        onClearTerminal = onClearTerminal,
                        installDesktopLabel = installDesktopLabel,
                        desktopIdle = desktopIdle,
                        onInstallDesktop = onInstallDesktop,
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
            val isError = state.status is ComputerStatus.Error
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (isError) {
                        ComputerScreenModel.statusLine(state)
                    } else {
                        stringResource(R.string.cos_header_subtitle)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TAG_STATUS),
                )
            }
            if (resources != null) ResourceStripRow(resources)
            ComputerTabRow(tab = tab, onTab = { tab = it })
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (tab) {
                    ComputerTab.DESKTOP -> Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            desktopPanel?.invoke()
                        }
                        CollapsedTerminalRow(
                            lastLine = ComputerScreenModel.lastTerminalLine(state.terminal),
                            onExpand = { tab = ComputerTab.TERMINAL },
                        )
                    }
                    ComputerTab.TERMINAL -> CosTerminalOutput(
                        text = state.terminal,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ComputerTab.ACTIVITY -> Box(Modifier.fillMaxSize()) {
                        if (activityPanel != null) {
                            activityPanel.invoke()
                        } else {
                            Text(
                                stringResource(R.string.cos_activity_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
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
    onOtherComputers: (() -> Unit)?,
    onTurnOff: (() -> Unit)?,
    state: ComputerUiState,
    onEnsureRootfs: () -> Unit,
    onReinstallRootfs: () -> Unit,
    onSmoke: () -> Unit,
    onPrune: () -> Unit,
    onInstallRecipe: (String) -> Unit,
    onRotateSession: () -> Unit,
    onClearTerminal: () -> Unit,
    installDesktopLabel: String,
    desktopIdle: Boolean,
    onInstallDesktop: () -> Unit,
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
            ComputerScreenModel.menuItems(state, desktopIdle).forEach { item ->
                val label = when (item.action) {
                    Action.INSTALL_ROOTFS -> stringResource(R.string.cos_ensure_rootfs)
                    Action.REINSTALL_ROOTFS -> if (reinstallArmed) {
                        stringResource(R.string.cos_reinstall_rootfs_confirm)
                    } else {
                        stringResource(R.string.cos_reinstall_rootfs)
                    }
                    Action.INSTALL_DESKTOP -> installDesktopLabel
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
                            Action.INSTALL_DESKTOP -> { close(); onInstallDesktop() }
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
            if (onOtherComputers != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remote_title)) },
                    modifier = Modifier.testTag(TAG_OTHER_COMPUTERS),
                    onClick = { close(); onOtherComputers() },
                )
            }
            if (onTurnOff != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cos_turn_off)) },
                    modifier = Modifier.testTag(TAG_TURN_OFF),
                    onClick = { close(); onTurnOff() },
                )
            }
        }
    }
}

/** Values for the CPU / Memory / COS storage strip; already formatted. */
data class CosResourceStrip(val cpu: String, val memory: String, val storage: String)

enum class ComputerTab { DESKTOP, TERMINAL, ACTIVITY }

@Composable
private fun StatusPill(label: String, active: Boolean) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (active) RunningGreen else fg.copy(alpha = 0.6f)),
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun ResourceStripRow(res: CosResourceStrip) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResourceCell(Icons.Outlined.Memory, stringResource(R.string.cos_res_cpu), res.cpu, Modifier.weight(1f))
            VerticalDivider(Modifier.height(32.dp))
            ResourceCell(Icons.Outlined.DeveloperBoard, stringResource(R.string.cos_res_memory), res.memory, Modifier.weight(1f))
            VerticalDivider(Modifier.height(32.dp))
            ResourceCell(Icons.Outlined.Storage, stringResource(R.string.cos_res_storage), res.storage, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResourceCell(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

@Composable
private fun ComputerTabRow(tab: ComputerTab, onTab: (ComputerTab) -> Unit) {
    val tabs = listOf(
        Triple(ComputerTab.DESKTOP, Icons.Outlined.DesktopWindows, R.string.cos_tab_desktop),
        Triple(ComputerTab.TERMINAL, Icons.Outlined.Terminal, R.string.cos_tab_terminal),
        Triple(ComputerTab.ACTIVITY, Icons.Outlined.BarChart, R.string.cos_tab_activity),
    )
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        tabs.forEachIndexed { i, (t, icon, label) ->
            SegmentedButton(
                selected = tab == t,
                onClick = { onTab(t) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = tabs.size),
                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("cos_tab_" + t.name.lowercase()),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

@Composable
private fun CollapsedTerminalRow(lastLine: String, onExpand: () -> Unit) {
    Surface(
        onClick = onExpand,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                lastLine.ifBlank { stringResource(R.string.cos_terminal_collapsed) },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.cos_tab_terminal))
        }
    }
}

private val RunningGreen = Color(0xFF4CD964)

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
internal const val TAG_TURN_OFF = "cos_turn_off"
internal const val TAG_OTHER_COMPUTERS = "cos_other_computers"
internal const val TAG_MENU = "cos_menu"
internal const val TAG_STATUS = "cos_status"
internal const val TAG_TERMINAL = "cos_terminal"
internal const val TAG_INPUT = "cos_input"
internal const val TAG_SEND = "cos_send"
internal const val TAG_STOP = "cos_stop"
internal fun menuTag(action: Action): String = "cos_menu_" + action.name.lowercase()
