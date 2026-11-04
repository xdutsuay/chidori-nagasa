package com.druk.lmplayground.computer

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.druk.lmplayground.R

/** Additive status line for Computer screen — does not own layout chrome. */
@Composable
fun CosRootfsStatusLine(
    mode: String?,
    usedBytes: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text = CosRootfsInfo.statusLine(mode, usedBytes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Two-step reinstall control (no dialog): tap once to arm, tap again to confirm.
 * Armed label turns error-colored "Confirm reinstall".
 */
@Composable
fun CosRootfsReinstallButton(
    enabled: Boolean,
    onConfirmReinstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) armed = false
    }
    OutlinedButton(
        onClick = {
            if (armed) {
                armed = false
                onConfirmReinstall()
            } else {
                armed = true
            }
        },
        enabled = enabled,
        colors = if (armed) {
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
        modifier = modifier,
    ) {
        Text(
            if (armed) {
                stringResource(R.string.cos_reinstall_rootfs_confirm)
            } else {
                stringResource(R.string.cos_reinstall_rootfs)
            },
        )
    }
}
