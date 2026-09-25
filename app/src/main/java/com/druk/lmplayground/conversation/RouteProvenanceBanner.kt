package com.druk.lmplayground.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Always-visible route label so the user can tell whether text stays on the
 * phone or leaves it. Warning colors when [leavesDevice] is true.
 */
@Composable
fun RouteProvenanceBanner(
    text: String,
    leavesDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (leavesDevice) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (leavesDevice) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = fg,
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
