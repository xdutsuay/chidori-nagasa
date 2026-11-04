package com.druk.lmplayground.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.ChatRoute
import com.druk.lmplayground.data.ChatSessionEntity

/** Hub destinations reachable from the side menu (pairing untouched). */
enum class HubDestination {
    CHATS,
    CHIDORI_DESKTOP,
    ASK_DESKTOP,
    NEEDS_YOU,
    COMPUTER,
    CLOUD_APIS,
    USAGE,
    MODELS,
    SETTINGS,
}

@Composable
fun SessionListDrawer(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    needsYouBadgeCount: Int = 0,
    sessionFilter: HubSessionFilter = HubSessionFilter.ALL,
    onSessionFilterChanged: (HubSessionFilter) -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChanged: (String) -> Unit = {},
    usageFooterText: String? = null,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onDestinationSelected: (HubDestination) -> Unit,
    onSettingsClicked: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        SessionListContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            needsYouBadgeCount = needsYouBadgeCount,
            sessionFilter = sessionFilter,
            onSessionFilterChanged = onSessionFilterChanged,
            searchQuery = searchQuery,
            onSearchQueryChanged = onSearchQueryChanged,
            usageFooterText = usageFooterText,
            onSessionSelected = onSessionSelected,
            onDeleteSession = onDeleteSession,
            onRenameSession = onRenameSession,
            onPinSession = onPinSession,
            onDestinationSelected = onDestinationSelected,
            onSettingsClicked = onSettingsClicked,
        )
    }
}

@Composable
fun PermanentSessionList(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    needsYouBadgeCount: Int = 0,
    sessionFilter: HubSessionFilter = HubSessionFilter.ALL,
    onSessionFilterChanged: (HubSessionFilter) -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChanged: (String) -> Unit = {},
    usageFooterText: String? = null,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onDestinationSelected: (HubDestination) -> Unit,
    onSettingsClicked: () -> Unit,
    width: Dp = 320.dp,
) {
    PermanentDrawerSheet(
        modifier = Modifier.width(width),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 12.dp, top = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
        ) {
            SessionListContent(
                sessions = sessions,
                currentSessionId = currentSessionId,
                needsYouBadgeCount = needsYouBadgeCount,
                sessionFilter = sessionFilter,
                onSessionFilterChanged = onSessionFilterChanged,
                searchQuery = searchQuery,
                onSearchQueryChanged = onSearchQueryChanged,
                usageFooterText = usageFooterText,
                onSessionSelected = onSessionSelected,
                onDeleteSession = onDeleteSession,
                onRenameSession = onRenameSession,
                onPinSession = onPinSession,
                onDestinationSelected = onDestinationSelected,
                onSettingsClicked = onSettingsClicked,
                compact = true,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListContent(
    sessions: List<ChatSessionEntity>,
    currentSessionId: String?,
    needsYouBadgeCount: Int,
    sessionFilter: HubSessionFilter,
    onSessionFilterChanged: (HubSessionFilter) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    usageFooterText: String?,
    onSessionSelected: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onPinSession: (String, Boolean) -> Unit,
    onDestinationSelected: (HubDestination) -> Unit,
    onSettingsClicked: () -> Unit,
    compact: Boolean = false,
) {
    var contextMenuSessionId by remember { mutableStateOf<String?>(null) }
    var renameDialogSession by remember { mutableStateOf<ChatSessionEntity?>(null) }
    val filtered = remember(sessions, sessionFilter, searchQuery) {
        filterSessionsForHub(sessions, sessionFilter, searchQuery)
    }
    val badgeText = formatNeedsYouBadge(needsYouBadgeCount)

    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 40.dp else 64.dp)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onSettingsClicked) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .imePadding(),
        ) {
            item(key = "dest_chats") {
                HubDestinationRow(
                    label = stringResource(R.string.conversations),
                    icon = Icons.Outlined.Chat,
                    selected = true,
                    onClick = { onDestinationSelected(HubDestination.CHATS) },
                )
            }
            item(key = "dest_chidori") {
                HubDestinationRow(
                    label = stringResource(R.string.chidori_desktop),
                    icon = Icons.Outlined.Computer,
                    onClick = { onDestinationSelected(HubDestination.CHIDORI_DESKTOP) },
                )
            }
            item(key = "dest_ask_desktop") {
                HubDestinationRow(
                    label = stringResource(R.string.hub_ask_desktop),
                    icon = Icons.Outlined.Chat,
                    onClick = { onDestinationSelected(HubDestination.ASK_DESKTOP) },
                )
            }
            item(key = "dest_needs_you") {
                HubDestinationRow(
                    label = stringResource(R.string.chidori_run_needs_you),
                    icon = Icons.Outlined.NotificationsActive,
                    badge = badgeText,
                    onClick = { onDestinationSelected(HubDestination.NEEDS_YOU) },
                )
            }
            item(key = "dest_computer") {
                HubDestinationRow(
                    label = stringResource(R.string.hub_computer),
                    icon = Icons.Outlined.Computer,
                    onClick = { onDestinationSelected(HubDestination.COMPUTER) },
                )
            }
            item(key = "dest_cloud") {
                HubDestinationRow(
                    label = stringResource(R.string.cloud_apis),
                    icon = Icons.Outlined.Api,
                    onClick = { onDestinationSelected(HubDestination.CLOUD_APIS) },
                )
            }
            item(key = "dest_usage") {
                HubDestinationRow(
                    label = stringResource(R.string.usage_title),
                    icon = Icons.Outlined.BarChart,
                    onClick = { onDestinationSelected(HubDestination.USAGE) },
                )
            }
            item(key = "dest_models") {
                HubDestinationRow(
                    label = stringResource(R.string.models),
                    icon = Icons.Outlined.Memory,
                    onClick = { onDestinationSelected(HubDestination.MODELS) },
                )
            }
            item(key = "dest_settings") {
                HubDestinationRow(
                    label = stringResource(R.string.settings),
                    icon = Icons.Outlined.Settings,
                    onClick = { onDestinationSelected(HubDestination.SETTINGS) },
                )
            }

            item(key = "search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChanged,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.hub_search_chats)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            item(key = "filters") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = sessionFilter == HubSessionFilter.ALL,
                        onClick = { onSessionFilterChanged(HubSessionFilter.ALL) },
                        label = { Text(stringResource(R.string.hub_filter_all)) },
                    )
                    FilterChip(
                        selected = sessionFilter == HubSessionFilter.ON_DEVICE,
                        onClick = { onSessionFilterChanged(HubSessionFilter.ON_DEVICE) },
                        label = { Text(stringResource(R.string.hub_filter_on_device)) },
                    )
                    FilterChip(
                        selected = sessionFilter == HubSessionFilter.DESKTOP,
                        onClick = { onSessionFilterChanged(HubSessionFilter.DESKTOP) },
                        label = { Text(stringResource(R.string.hub_filter_desktop)) },
                    )
                    FilterChip(
                        selected = sessionFilter == HubSessionFilter.CLOUD,
                        onClick = { onSessionFilterChanged(HubSessionFilter.CLOUD) },
                        label = { Text(stringResource(R.string.hub_filter_cloud)) },
                    )
                }
                Text(
                    text = stringResource(R.string.conversations),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                )
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = if (sessionFilter == HubSessionFilter.DESKTOP) {
                                stringResource(R.string.hub_desktop_empty)
                            } else {
                                stringResource(R.string.hub_sessions_empty)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sessionFilter == HubSessionFilter.DESKTOP) {
                            TextButton(
                                onClick = { onDestinationSelected(HubDestination.CHIDORI_DESKTOP) },
                            ) {
                                Text(stringResource(R.string.hub_open_chidori_desktop))
                            }
                        }
                    }
                }
            }

            items(filtered, key = { it.id }) { session ->
                val isSelected = session.id == currentSessionId
                val showMenu = contextMenuSessionId == session.id

                Surface(
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .combinedClickable(
                            onClick = { onSessionSelected(session.id) },
                            onLongClick = { contextMenuSessionId = session.id },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (session.route == ChatRoute.CLOUD) {
                                Text(
                                    text = stringResource(R.string.cloud_session_remote),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (session.pinned) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.pinned_label),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { contextMenuSessionId = null },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (session.pinned) {
                                        stringResource(R.string.unpin)
                                    } else {
                                        stringResource(R.string.pin)
                                    },
                                )
                            },
                            onClick = {
                                onPinSession(session.id, !session.pinned)
                                contextMenuSessionId = null
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.PushPin,
                                    contentDescription = null,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = {
                                renameDialogSession = session
                                contextMenuSessionId = null
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = null,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = {
                                onDeleteSession(session.id)
                                contextMenuSessionId = null
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (!usageFooterText.isNullOrBlank()) {
            TextButton(
                onClick = { onDestinationSelected(HubDestination.USAGE) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = usageFooterText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    renameDialogSession?.let { session ->
        var text by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameDialogSession = null },
            title = { Text(stringResource(R.string.rename_conversation)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameSession(session.id, text.trim())
                        renameDialogSession = null
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubDestinationRow(
    label: String,
    icon: ImageVector,
    selected: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.error,
                    shape = CircleShape,
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
