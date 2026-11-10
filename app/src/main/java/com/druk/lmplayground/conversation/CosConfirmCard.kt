package com.druk.lmplayground.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.druk.lmplayground.R
import com.druk.lmplayground.tools.CosConfirmGate

/**
 * Review-before-act card: shown while a COS tool waits in [CosConfirmGate.await].
 * Allow runs the action; Deny (or ignoring it until the timeout) skips it.
 */
@Composable
fun CosConfirmCard(gate: CosConfirmGate = CosConfirmGate.shared, modifier: Modifier = Modifier) {
    val pending by gate.pending.collectAsStateWithLifecycle()
    val request = pending ?: return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.cos_confirm_title, request.toolName),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = request.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = { gate.resolve(request.id, approved = true) }) {
                    Text(stringResource(R.string.cos_confirm_allow))
                }
                OutlinedButton(onClick = { gate.resolve(request.id, approved = false) }) {
                    Text(stringResource(R.string.cos_confirm_deny))
                }
            }
        }
    }
}
