package com.druk.lmplayground.computer.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Compact Desktop controls on the Computer screen: shared [DesktopState] label,
 * Start/Stop/Shot, install progress, and Retry on [DesktopState.Error].
 * Live framebuffer viewer sits below when Running.
 */
@Composable
fun DesktopDebugPanel(
    panel: DesktopPanelState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScreenshot: () -> Unit,
    onRetry: () -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().testTag(TAG_DESKTOP_PANEL),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    panel.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(TAG_DESKTOP_STATE),
                )
                if (panel.state is DesktopState.Error) {
                    TextButton(
                        onClick = onRetry,
                        enabled = enabled,
                        modifier = Modifier.testTag(TAG_DESKTOP_RETRY),
                    ) { Text(stringResource(R.string.cos_desktop_retry)) }
                }
                if (panel.canStop) {
                    TextButton(onClick = onStop, modifier = Modifier.testTag(TAG_DESKTOP_STOP)) {
                        Text(stringResource(R.string.cos_stop))
                    }
                } else {
                    TextButton(
                        onClick = onStart,
                        enabled = enabled && panel.canStart,
                        modifier = Modifier.testTag(TAG_DESKTOP_START),
                    ) { Text(stringResource(R.string.cos_desktop_start)) }
                }
                TextButton(
                    onClick = onScreenshot,
                    enabled = panel.state is DesktopState.Running,
                    modifier = Modifier.testTag(TAG_DESKTOP_SCREENSHOT),
                ) { Text(stringResource(R.string.cos_desktop_shot)) }
            }
            val installing = panel.state as? DesktopState.Installing
            if (installing != null) {
                val p = installing.progress
                if (p != null) {
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .testTag(TAG_DESKTOP_PROGRESS),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .testTag(TAG_DESKTOP_PROGRESS),
                    )
                }
            }
            panel.screenshotInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            panel.screenshot?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.cos_desktop_screenshot_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                )
            }
        }
    }
}

/** Size-first confirm; the flavour / Firefox choices live in DesktopExtrasDialog. */
@Composable
fun DesktopInstallDialog(
    prompt: DesktopInstallPolicy.Prompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column {
                Text(prompt.message)
                prompt.blockedReason?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TAG_DESKTOP_BLOCKED))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = prompt.canInstall,
                modifier = Modifier.testTag(TAG_DESKTOP_CONFIRM),
            ) {
                Text(
                    prompt.confirmLabel,
                    color = if (prompt.meteredWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal const val TAG_DESKTOP_PANEL = "cos_desktop_panel"
internal const val TAG_DESKTOP_STATE = "cos_desktop_state"
internal const val TAG_DESKTOP_START = "cos_desktop_start"
internal const val TAG_DESKTOP_STOP = "cos_desktop_stop"
internal const val TAG_DESKTOP_SCREENSHOT = "cos_desktop_screenshot"
internal const val TAG_DESKTOP_CONFIRM = "cos_desktop_confirm"
internal const val TAG_DESKTOP_BLOCKED = "cos_desktop_blocked"
internal const val TAG_DESKTOP_PROGRESS = "cos_desktop_progress"
internal const val TAG_DESKTOP_RETRY = "cos_desktop_retry"
