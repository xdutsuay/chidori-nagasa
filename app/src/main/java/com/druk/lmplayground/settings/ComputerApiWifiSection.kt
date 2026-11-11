package com.druk.lmplayground.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.computer.api.ComputerApis
import com.druk.lmplayground.computer.api.QrBitmap
import com.druk.lmplayground.computer.api.WifiShareState
import com.druk.lmplayground.conversation.CosConfirmCard
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Developer options: the Wi-Fi (HTTPS, paired clients) Computer API session. */
@Composable
internal fun ComputerApiWifiSection() {
    val share = ComputerApis.wifi(LocalContext.current)
    val state by share.state.collectAsState()
    val code by share.pairing.code.collectAsState()
    val clients by share.pairing.clients.collectAsState()
    val scope = rememberCoroutineScope()
    var allowControl by remember { mutableStateOf(false) }
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val withNotificationPermission = rememberWithApprovalNotificationPermission()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.computer_api_wifi_title), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        when (val s = state) {
            is WifiShareState.Shared -> {
                val e = s.endpoint
                val uri = remember(e, code) { share.pairingUri() }
                if (uri != null) {
                    val qr = remember(uri) { QrBitmap.encode(uri, QR_SIZE_PX).asImageBitmap() }
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.computer_api_wifi_qr_description),
                        modifier = Modifier.size(QR_SIZE_DP.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                SelectionContainer {
                    Column {
                        Text(
                            stringResource(
                                R.string.computer_api_wifi_address,
                                e.host, e.port, e.publicKeyPin, time.format(Date(e.expiresAtMs)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val c = code
                        Text(
                            if (c != null) {
                                stringResource(R.string.computer_api_wifi_code, c.code, time.format(Date(c.expiresAtMs)))
                            } else {
                                stringResource(R.string.computer_api_wifi_no_code)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    stringResource(R.string.computer_api_wifi_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { share.pairing.issueCode() }) {
                    Text(stringResource(R.string.computer_api_wifi_new_code))
                }
                if (clients.isNotEmpty()) {
                    Text(stringResource(R.string.computer_api_wifi_clients), style = MaterialTheme.typography.labelLarge)
                    clients.forEach { client ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.computer_api_wifi_client, client.name, client.address),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { share.pairing.revoke(client.id) }) {
                                Text(stringResource(R.string.computer_api_wifi_revoke))
                            }
                        }
                    }
                }
                if (e.control) {
                    Text(
                        stringResource(R.string.computer_api_usb_control_on),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CosConfirmCard()
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { scope.launch { share.stop() } }) {
                    Text(stringResource(R.string.computer_api_wifi_stop))
                }
            }
            else -> {
                if (s is WifiShareState.Error) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    stringResource(R.string.computer_api_wifi_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowControl, onCheckedChange = { allowControl = it })
                    Text(stringResource(R.string.computer_api_usb_allow_control), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    withNotificationPermission(allowControl) { scope.launch { share.start(allowControl = allowControl) } }
                }) {
                    Text(stringResource(R.string.computer_api_wifi_start))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private const val QR_SIZE_PX = 512
private const val QR_SIZE_DP = 200
