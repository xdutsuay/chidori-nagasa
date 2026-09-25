package com.druk.lmplayground.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudApisScreen(
    providers: List<CloudProviderEntity>,
    status: String?,
    testing: Boolean,
    hasKey: (String) -> Boolean,
    onBackClick: () -> Unit,
    onSave: (presetId: String?, name: String, baseUrl: String, apiKey: String, allowHttp: Boolean, existingId: String?) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onClearStatus: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_apis)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        CloudApisContent(
            providers = providers,
            status = status,
            testing = testing,
            hasKey = hasKey,
            onSave = onSave,
            onDelete = onDelete,
            onTest = onTest,
            onOpenChat = onOpenChat,
            onClearStatus = onClearStatus,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
fun CloudApisContent(
    providers: List<CloudProviderEntity>,
    status: String?,
    testing: Boolean,
    hasKey: (String) -> Boolean,
    onSave: (presetId: String?, name: String, baseUrl: String, apiKey: String, allowHttp: Boolean, existingId: String?) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onClearStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var presetId by remember { mutableStateOf(CloudPresets.OPENROUTER.id) }
    var name by remember { mutableStateOf(CloudPresets.OPENROUTER.displayName) }
    var baseUrl by remember { mutableStateOf(CloudPresets.OPENROUTER.baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.cloud_apis_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.cloud_apis_preset), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth()) {
            for (preset in CloudPresets.ALL) {
                FilterChip(
                    selected = presetId == preset.id && editingId == null,
                    onClick = {
                        editingId = null
                        presetId = preset.id
                        name = preset.displayName
                        baseUrl = preset.baseUrl
                        allowHttp = false
                        onClearStatus()
                    },
                    label = { Text(preset.displayName) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            FilterChip(
                selected = presetId == "custom" && editingId == null,
                onClick = {
                    editingId = null
                    presetId = "custom"
                    name = "Custom"
                    baseUrl = "https://"
                    onClearStatus()
                },
                label = { Text(stringResource(R.string.cloud_apis_custom)) },
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.cloud_apis_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.cloud_apis_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.cloud_apis_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(R.string.cloud_apis_toggle_key),
                    )
                }
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.cloud_apis_allow_http),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = allowHttp, onCheckedChange = { allowHttp = it })
        }
        if (allowHttp) {
            Text(
                stringResource(R.string.cloud_apis_allow_http_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                onSave(
                    if (presetId == "custom") null else presetId,
                    name,
                    baseUrl,
                    apiKey,
                    allowHttp,
                    editingId,
                )
                apiKey = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cloud_apis_save))
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(status, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cloud_apis_saved), style = MaterialTheme.typography.titleMedium)
        if (providers.isEmpty()) {
            Text(
                stringResource(R.string.cloud_apis_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (p in providers) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleSmall)
                    Text(p.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (hasKey(p.id)) {
                            stringResource(R.string.cloud_apis_key_saved)
                        } else {
                            stringResource(R.string.cloud_apis_key_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = {
                            editingId = p.id
                            presetId = "custom"
                            name = p.name
                            baseUrl = p.baseUrl
                            allowHttp = p.allowHttp
                            apiKey = ""
                            onClearStatus()
                        }) { Text(stringResource(R.string.cloud_apis_edit)) }
                        TextButton(onClick = { onTest(p.id) }, enabled = !testing) {
                            Text(stringResource(R.string.cloud_apis_test))
                        }
                        TextButton(onClick = { onOpenChat(p.id) }) {
                            Text(stringResource(R.string.cloud_apis_open_chat))
                        }
                        TextButton(onClick = { onDelete(p.id) }) {
                            Text(stringResource(R.string.cloud_apis_delete))
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = {
                editingId = null
                name = ""
                baseUrl = ""
                apiKey = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.cloud_apis_clear_form))
        }
    }
}
