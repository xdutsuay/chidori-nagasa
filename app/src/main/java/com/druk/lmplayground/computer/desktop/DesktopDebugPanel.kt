package com.druk.lmplayground.computer.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * TEMPORARY (CP2 M1) Desktop debug section on the Computer screen: state, Start/Stop and
 * a screenshot of the real Xvfb frame. Replaced by the desktop viewer in a later milestone.
 */
@Composable
fun DesktopDebugPanel(
    panel: DesktopPanelState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScreenshot: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().testTag(TAG_DESKTOP_PANEL),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Desktop (debug): ${panel.label}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(TAG_DESKTOP_STATE),
                )
                if (panel.canStop) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.testTag(TAG_DESKTOP_STOP)) { Text("Stop") }
                } else {
                    OutlinedButton(
                        onClick = onStart,
                        enabled = enabled && panel.canStart,
                        modifier = Modifier.testTag(TAG_DESKTOP_START),
                    ) { Text("Start") }
                }
                OutlinedButton(
                    onClick = onScreenshot,
                    enabled = panel.state is DesktopState.Running,
                    modifier = Modifier.testTag(TAG_DESKTOP_SCREENSHOT),
                ) { Text("Shot") }
            }
            panel.screenshotInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            panel.screenshot?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Desktop screenshot",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                )
            }
        }
    }
}

/** Size-first confirm; metered / mobile data gets an explicit "Download anyway". */
@Composable
fun DesktopInstallDialog(
    prompt: DesktopInstallPolicy.Prompt,
    withFirefox: Boolean,
    onWithFirefoxChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(prompt.title) },
        text = {
            Column {
                Text(prompt.message)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withFirefox, onCheckedChange = onWithFirefoxChange)
                    Text("Also install Firefox ESR (opt-in, large)")
                }
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
