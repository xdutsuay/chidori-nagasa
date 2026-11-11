package com.druk.lmplayground.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.computer.ComputerPrefs
import com.druk.lmplayground.computer.CosConstants
import com.druk.lmplayground.computer.api.ApiShareState
import com.druk.lmplayground.computer.api.ComputerApis
import com.druk.lmplayground.conversation.CosConfirmCard
import com.druk.lmplayground.computer.desktop.DesktopBridges
import com.druk.lmplayground.computer.desktop.DesktopCapability
import com.druk.lmplayground.computer.desktop.ViewerState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Developer options: the switch, and (when on) the USB-only desktop viewer. */
@Composable
fun DeveloperOptionsSection() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(CosConstants.PREFS_NAME, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(ComputerPrefs.isDeveloperMode(prefs)) }
    val bridge = DesktopBridges.bridge
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.developer_options_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.developer_options_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = enabled, onCheckedChange = { on ->
            enabled = on
            ComputerPrefs.setDeveloperMode(prefs, on)
            if (!on) scope.launch {
                bridge?.stopViewer()
                ComputerApis.share(context).stop()
                ComputerApis.wifi(context).stop()
            }
        })
    }
    if (!enabled) return
    ComputerApiUsbSection()
    ComputerApiWifiSection()
    if (bridge == null || DesktopCapability.VIEWER !in bridge.capabilities()) return

    val viewer by bridge.viewer.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.viewer_usb_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        when (val v = viewer) {
            is ViewerState.Shared -> {
                val e = v.endpoint
                val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.expiresAtMs))
                SelectionContainer {
                    Text(
                        stringResource(R.string.viewer_usb_shared, e.port, e.password, until),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { bridge.stopViewer() } }) {
                    Text(stringResource(R.string.viewer_usb_stop))
                }
            }
            ViewerState.Starting -> Text(stringResource(R.string.viewer_usb_starting))
            else -> {
                if (v is ViewerState.Error) {
                    Text(v.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    stringResource(R.string.viewer_usb_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { scope.launch { bridge.startViewer() } }) {
                    Text(stringResource(R.string.viewer_usb_start))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ComputerApiUsbSection() {
    val share = ComputerApis.share(LocalContext.current)
    val state by share.state.collectAsState()
    val scope = rememberCoroutineScope()
    var allowControl by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.computer_api_usb_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        when (val s = state) {
            is ApiShareState.Shared -> {
                val e = s.endpoint
                val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.expiresAtMs))
                SelectionContainer {
                    Text(
                        stringResource(R.string.computer_api_usb_shared, e.port, e.token, until),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (e.control) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.computer_api_usb_control_on),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CosConfirmCard()
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { share.stop() } }) {
                    Text(stringResource(R.string.computer_api_usb_stop))
                }
            }
            else -> {
                if (s is ApiShareState.Error) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    stringResource(R.string.computer_api_usb_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowControl, onCheckedChange = { allowControl = it })
                    Text(
                        stringResource(R.string.computer_api_usb_allow_control),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { scope.launch { share.start(allowControl = allowControl) } }) {
                    Text(stringResource(R.string.computer_api_usb_start))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
