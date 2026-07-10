package com.druk.lmplayground.coordinator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.RemoteChatMessage

/**
 * Remote chat surface (PRD.md §6.4) — routed through the paired desktop's
 * coordinator, deliberately visually distinct from the on-device chat
 * (persistent "via <desktop>" banner: the privacy properties differ, and
 * the user must always be able to tell which surface they're in — PRD §7).
 *
 * Rendered in place inside the Chidori screen like the monitor; see
 * ChidoriMonitorScreen's kdoc for why (tablet detail pane has no
 * NavController). [showBackHeader] follows the same convention.
 *
 * Messages come exclusively from the server stream — the desktop echoes the
 * user's own messages back with `from_user=true` (WIRE_CONTRACT.md), so
 * there is no local echo to reconcile or de-duplicate.
 */
@Composable
fun ChidoriChatContent(
    displayName: String,
    messages: List<RemoteChatMessage>,
    connectionState: CoordinatorConnectionState,
    input: String,
    showBackHeader: Boolean,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (showBackHeader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(displayName, style = MaterialTheme.typography.titleLarge)
            }
        }

        ConnectionBanner(displayName = displayName, connectionState = connectionState)

        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                placeholder = { Text(stringResource(R.string.chidori_chat_input_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSendClick,
                enabled = input.isNotBlank() && connectionState == CoordinatorConnectionState.CONNECTED,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chidori_chat_send),
                )
            }
        }
    }
}

/**
 * Always-visible provenance/status strip. Never removed while connected —
 * this is the "clearly labeled as routed through the paired desktop"
 * requirement, not just a transient connection indicator.
 */
@Composable
private fun ConnectionBanner(
    displayName: String,
    connectionState: CoordinatorConnectionState,
) {
    val (text, container) = when (connectionState) {
        CoordinatorConnectionState.CONNECTED ->
            stringResource(R.string.chidori_chat_via_desktop, displayName) to
                MaterialTheme.colorScheme.secondaryContainer
        CoordinatorConnectionState.CONNECTING ->
            stringResource(R.string.chidori_chat_connecting) to
                MaterialTheme.colorScheme.surfaceVariant
        else ->
            stringResource(R.string.chidori_chat_disconnected, displayName) to
                MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ChatBubble(message: RemoteChatMessage) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .align(if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
