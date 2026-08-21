package com.druk.lmplayground.coordinator.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.Message as ConversationMessage
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.RemoteChatMessage

/**
 * Remote chat surface (PRD.md §6.4) — routed through the paired desktop's
 * coordinator. Bubbles reuse the on-device [Message] composable so replies
 * render like native LMPlayground chat (markdown, selection, share/copy).
 * The persistent "via &lt;desktop&gt;" banner stays — privacy provenance
 * (PRD §7) must remain visible even when the bubble chrome matches local chat.
 *
 * Streamed reply frames are coalesced in [ChidoriViewModel] (KMA-127) so
 * word-sized deltas do not each get their own bubble + action row.
 *
 * Rendered in place inside the Chidori screen like the monitor; see
 * ChidoriMonitorScreen's kdoc for why (tablet detail pane has no
 * NavController). [showBackHeader] follows the same convention.
 *
 * Messages come exclusively from the server stream — the desktop echoes the
 * user's own messages back with `from_user=true` (WIRE_CONTRACT.md), so
 * there is no local echo to reconcile or de-duplicate.
 *
 * Input stays ViewModel-controlled (not [com.druk.lmplayground.conversation.UserInput])
 * so a failed send keeps the draft (PRD §6.4).
 */
@Composable
fun ChidoriChatContent(
    displayName: String,
    messages: List<RemoteChatMessage>,
    connectionState: CoordinatorConnectionState,
    input: String,
    awaitingReply: Boolean,
    showBackHeader: Boolean,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.imePadding()) {
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
                Spacer(modifier = Modifier.width(4.dp))
                Text(displayName, style = MaterialTheme.typography.titleLarge)
            }
        }

        ConnectionBanner(displayName = displayName, connectionState = connectionState)

        val listState = rememberLazyListState()
        val scrollTick = messages.lastOrNull()?.let { it.id to it.text.length } ?: ("" to 0)
        LaunchedEffect(messages.size, scrollTick) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (messages.isEmpty() && !awaitingReply) {
                Text(
                    text = stringResource(R.string.chidori_chat_empty, displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(messages, key = { it.id }) { remote ->
                        val msg = remember(remote) { remote.toConversationMessage() }
                        com.druk.lmplayground.conversation.Message(
                            msg = msg,
                            isUserMe = remote.fromUser,
                            showActions = !remote.fromUser,
                        )
                    }
                    if (awaitingReply) {
                        item(key = "awaiting-reply") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.chidori_chat_replying),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
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
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onSendClick,
                enabled = input.isNotBlank() &&
                    connectionState == CoordinatorConnectionState.CONNECTED &&
                    !awaitingReply,
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

private fun RemoteChatMessage.toConversationMessage(): ConversationMessage = ConversationMessage(
    author = if (fromUser) "User" else "Assistant",
    content = text,
    timestamp = sentAtEpochMillis,
    // Wire ids are strings; conversation.Message keys on Long. Stable for a
    // session as long as the desktop reuses the same id per message.
    id = id.hashCode().toLong(),
)
