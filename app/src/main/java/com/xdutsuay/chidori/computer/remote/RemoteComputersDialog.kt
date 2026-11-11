package com.xdutsuay.chidori.computer.remote

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xdutsuay.chidori.R
import com.xdutsuay.chidori.computer.desktop.DesktopBridges
import com.xdutsuay.chidori.computer.desktop.LocalDesktopBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PREFS = "remote_computers"
private const val PREFS_KEY = "computers"
private const val TEST_COMMAND = "uname -a"
private const val TEST_TIMEOUT_MS = 15_000

fun remoteComputerStore(context: Context): RemoteComputerStore {
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return RemoteComputerStore(
        backend = object : RemoteComputerBackend {
            override fun read(): String? = prefs.getString(PREFS_KEY, null)
            override fun write(json: String) {
                prefs.edit().putString(PREFS_KEY, json).apply()
            }
        },
        newId = { UUID.randomUUID().toString() },
    )
}

/** Full-screen "Other computers": add / test / copy key / rotate / delete (P4 slice 1). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteComputersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { remoteComputerStore(context) }
    val vault = remember { RemoteKeyVault(context) }
    val transport = remember { JschTransport() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var computers by remember { mutableStateOf(store.list()) }
    val messages = remember { mutableStateMapOf<String, String>() }
    var trustPrompt by remember { mutableStateOf<Pair<RemoteComputer, String>?>(null) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    var agentId by remember { mutableStateOf((DesktopBridges.bridge as? RemoteDesktopBridge)?.computer?.id) }

    fun refresh() {
        computers = store.list()
    }

    fun usePhone() {
        val remote = DesktopBridges.bridge as? RemoteDesktopBridge
        DesktopBridges.bridge = LocalDesktopBridge.get(context)
        agentId = null
        remote?.let { scope.launch { it.stop() } }
    }

    fun useForAgent(c: RemoteComputer) {
        messages[c.id] = context.getString(R.string.remote_agent_starting)
        scope.launch {
            val bridge = RemoteDesktopBridge(c, identity = { vault.loadOrCreate(c.id) })
            bridge.start()
                .onSuccess { running ->
                    val previous = DesktopBridges.bridge as? RemoteDesktopBridge
                    DesktopBridges.bridge = bridge
                    agentId = c.id
                    previous?.takeIf { it.computer.id != c.id }?.stop()
                    messages[c.id] = context.getString(
                        R.string.remote_agent_on,
                        running.display,
                        running.width,
                        running.height,
                    )
                }
                .onFailure { messages[c.id] = context.getString(R.string.remote_failed, it.message.orEmpty()) }
        }
    }

    fun test(c: RemoteComputer) {
        messages[c.id] = context.getString(R.string.remote_testing)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                transport.exec(c, vault.loadOrCreate(c.id), TEST_COMMAND, TEST_TIMEOUT_MS)
            }
            messages[c.id] = when (result) {
                is SshResult.Ok -> context.getString(
                    R.string.remote_ok,
                    result.exitCode,
                    result.stdout.trim().ifEmpty { result.stderr.trim() },
                )
                is SshResult.UnknownHostKey -> {
                    trustPrompt = c to result.fingerprint
                    context.getString(R.string.remote_not_pinned)
                }
                is SshResult.HostKeyChanged -> context.getString(R.string.remote_changed, result.pinned, result.presented)
                is SshResult.Failed -> context.getString(R.string.remote_failed, result.message)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.remote_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.remote_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            computers.firstOrNull { it.id == agentId }
                                ?.let { stringResource(R.string.remote_agent_uses, it.name) }
                                ?: stringResource(R.string.remote_agent_uses_phone),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (agentId != null) {
                            TextButton(onClick = { usePhone() }) { Text(stringResource(R.string.remote_use_phone)) }
                        }
                    }
                }
                if (computers.isEmpty()) {
                    Text(stringResource(R.string.remote_empty), style = MaterialTheme.typography.bodyMedium)
                }
                computers.forEach { c ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${c.user}@${c.host}:${c.port}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                c.hostKeyFingerprint?.let { stringResource(R.string.remote_pinned, it) }
                                    ?: stringResource(R.string.remote_not_pinned),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            messages[c.id]?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val id = withContext(Dispatchers.IO) { vault.loadOrCreate(c.id) }
                                        clipboard.setText(
                                            AnnotatedString(
                                                SshKeyFormat.authorizedKeysLine(
                                                    SshKeyGen.KEY_TYPE,
                                                    id.publicKeyBlob,
                                                    "cindi-" + c.name.filter { it.isLetterOrDigit() },
                                                ),
                                            ),
                                        )
                                        messages[c.id] = context.getString(R.string.remote_key_copied, c.host)
                                    }
                                }) { Text(stringResource(R.string.remote_copy_key)) }
                                Button(onClick = { test(c) }) { Text(stringResource(R.string.remote_test)) }
                                if (c.hostKeyFingerprint != null && agentId != c.id) {
                                    FilledTonalButton(onClick = { useForAgent(c) }) {
                                        Text(stringResource(R.string.remote_use_for_agent))
                                    }
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { vault.rotate(c.id) }
                                        messages[c.id] = context.getString(R.string.remote_rotated)
                                    }
                                }) { Text(stringResource(R.string.remote_rotate)) }
                                if (c.hostKeyFingerprint != null) {
                                    TextButton(onClick = {
                                        store.clearHostKey(c.id)
                                        refresh()
                                    }) { Text(stringResource(R.string.remote_forget_host_key)) }
                                }
                                TextButton(onClick = {
                                    if (agentId == c.id) usePhone()
                                    vault.delete(c.id)
                                    store.remove(c.id)
                                    messages.remove(c.id)
                                    refresh()
                                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }

                Text(stringResource(R.string.remote_add), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.remote_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.remote_field_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.remote_field_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                    OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.remote_field_user)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        val p = port.toIntOrNull() ?: 0
                        val bad = RemoteComputerValidation.validate(name, host, p, user)
                        if (bad.isNotEmpty()) {
                            formError = context.getString(R.string.remote_invalid, bad.joinToString { it.name.lowercase() })
                        } else {
                            val c = store.add(name, host, p, user)
                            scope.launch(Dispatchers.IO) { vault.loadOrCreate(c.id) }
                            name = ""
                            host = ""
                            port = "22"
                            user = ""
                            formError = null
                            refresh()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.remote_add)) }
            }
        }
    }

    trustPrompt?.let { (c, fp) ->
        AlertDialog(
            onDismissRequest = { trustPrompt = null },
            title = { Text(stringResource(R.string.remote_trust_title)) },
            text = { Text(stringResource(R.string.remote_trust_body, c.host, fp)) },
            confirmButton = {
                TextButton(onClick = {
                    trustPrompt = null
                    store.pinHostKey(c.id, fp)?.let { pinned ->
                        refresh()
                        test(pinned)
                    }
                }) { Text(stringResource(R.string.remote_trust)) }
            },
            dismissButton = {
                TextButton(onClick = { trustPrompt = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
