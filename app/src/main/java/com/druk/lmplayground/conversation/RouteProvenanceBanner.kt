package com.druk.lmplayground.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Always-visible route label so the user can tell whether text stays on the
 * phone or leaves it: a small centered pill, tinted when [leavesDevice].
 */
@Composable
fun RouteProvenanceBanner(
    text: String,
    leavesDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (leavesDevice) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (leavesDevice) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(50), color = bg) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = if (leavesDevice) Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
