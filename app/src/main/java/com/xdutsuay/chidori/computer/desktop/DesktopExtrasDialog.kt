package com.xdutsuay.chidori.computer.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xdutsuay.chidori.R

/**
 * Pre-install choices for the COS desktop: the session flavour (Openbox / XFCE) and
 * the optional Firefox ESR add-on. Shown before the size confirm
 * ([DesktopInstallDialog]); the chosen values are persisted via [DesktopExtrasPrefs]
 * and passed to [DesktopPanelViewModel.installPrompt] / [install].
 */
@Composable
fun DesktopExtrasDialog(
    initialFlavor: DesktopFlavor,
    initialFirefox: Boolean,
    onConfirm: (flavor: DesktopFlavor, firefox: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var flavor by remember { mutableStateOf(initialFlavor) }
    var firefox by remember { mutableStateOf(initialFirefox) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cos_desktop_extras_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.cos_desktop_extras_flavor_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                for (f in DesktopFlavor.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = flavor == f,
                                role = Role.RadioButton,
                                onClick = { flavor = f },
                            ),
                    ) {
                        RadioButton(selected = flavor == f, onClick = null)
                        Text(
                            text = when (f) {
                                DesktopFlavor.OPENBOX -> stringResource(R.string.cos_desktop_flavor_openbox)
                                DesktopFlavor.XFCE -> stringResource(R.string.cos_desktop_flavor_xfce)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Checkbox(checked = firefox, onCheckedChange = { firefox = it })
                    Text(
                        stringResource(R.string.cos_desktop_extras_firefox),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(flavor, firefox) }) {
                Text(stringResource(R.string.cos_desktop_extras_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cos_desktop_extras_dismiss))
            }
        },
    )
}
