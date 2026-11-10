@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.tools.CosConfirmPolicy
import com.druk.lmplayground.tools.Tool

/**
 * User-facing copy for a tool, keyed by [Tool.name]. The runtime
 * [Tool.description] is written for the model, not the user, so the settings
 * screen uses its own plain-language strings and a worked example.
 */
private data class ToolUiInfo(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val exampleRes: Int,
    val icon: ImageVector,
)

private val TOOL_UI: Map<String, ToolUiInfo> = mapOf(
    "run_javascript" to ToolUiInfo(
        R.string.tool_run_javascript_title,
        R.string.tool_run_javascript_desc,
        R.string.tool_run_javascript_example,
        Icons.Outlined.Code,
    ),
    "web_search" to ToolUiInfo(
        R.string.tool_web_search_title,
        R.string.tool_web_search_desc,
        R.string.tool_web_search_example,
        Icons.Outlined.Search,
    ),
    "web_fetch" to ToolUiInfo(
        R.string.tool_web_fetch_title,
        R.string.tool_web_fetch_desc,
        R.string.tool_web_fetch_example,
        Icons.Outlined.Public,
    ),
    "cos_exec" to ToolUiInfo(
        R.string.tool_cos_exec_title,
        R.string.tool_cos_exec_desc,
        R.string.tool_cos_exec_example,
        Icons.Outlined.Computer,
    ),
    "library_search" to ToolUiInfo(
        R.string.tool_library_search_title,
        R.string.tool_library_search_desc,
        R.string.tool_library_search_example,
        Icons.Outlined.LibraryBooks,
    ),
)

@Composable
fun ToolsScreen(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    onBackClick: () -> Unit,
    confirmMode: CosConfirmPolicy.Mode = CosConfirmPolicy.Mode.AUTO,
    onConfirmModeChanged: (CosConfirmPolicy.Mode) -> Unit = {},
    evidenceDogfood: Boolean = false,
    onEvidenceDogfoodChanged: (Boolean) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ToolsContent(
            tools = tools,
            enabledStates = enabledStates,
            onToolEnabledChanged = onToolEnabledChanged,
            confirmMode = confirmMode,
            onConfirmModeChanged = onConfirmModeChanged,
            evidenceDogfood = evidenceDogfood,
            onEvidenceDogfoodChanged = onEvidenceDogfoodChanged,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
fun ToolsContent(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    confirmMode: CosConfirmPolicy.Mode = CosConfirmPolicy.Mode.AUTO,
    onConfirmModeChanged: (CosConfirmPolicy.Mode) -> Unit = {},
    evidenceDogfood: Boolean = false,
    onEvidenceDogfoodChanged: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        tools.forEach { tool ->
            ToolRow(
                tool = tool,
                enabled = enabledStates[tool.name] ?: false,
                onToggle = { onToolEnabledChanged(tool.name, it) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        ConfirmPolicySection(
            confirmMode = confirmMode,
            onConfirmModeChanged = onConfirmModeChanged,
            evidenceDogfood = evidenceDogfood,
            onEvidenceDogfoodChanged = onEvidenceDogfoodChanged,
        )
    }
}

@Composable
private fun ConfirmPolicySection(
    confirmMode: CosConfirmPolicy.Mode,
    onConfirmModeChanged: (CosConfirmPolicy.Mode) -> Unit,
    evidenceDogfood: Boolean,
    onEvidenceDogfoodChanged: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.tools_confirm_section_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tools_confirm_section_blurb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            ConfirmModeChip(
                label = stringResource(R.string.tools_confirm_mode_auto),
                selected = confirmMode == CosConfirmPolicy.Mode.AUTO,
                onClick = { onConfirmModeChanged(CosConfirmPolicy.Mode.AUTO) },
            )
            Spacer(Modifier.width(8.dp))
            ConfirmModeChip(
                label = stringResource(R.string.tools_confirm_mode_ask_once),
                selected = confirmMode == CosConfirmPolicy.Mode.ASK_ONCE,
                onClick = { onConfirmModeChanged(CosConfirmPolicy.Mode.ASK_ONCE) },
            )
            Spacer(Modifier.width(8.dp))
            ConfirmModeChip(
                label = stringResource(R.string.tools_confirm_mode_always),
                selected = confirmMode == CosConfirmPolicy.Mode.ALWAYS,
                onClick = { onConfirmModeChanged(CosConfirmPolicy.Mode.ALWAYS) },
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvidenceDogfoodChanged(!evidenceDogfood) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tools_evidence_dogfood_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tools_evidence_dogfood_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = evidenceDogfood,
                onCheckedChange = onEvidenceDogfoodChanged,
            )
        }
    }
}

@Composable
private fun ConfirmModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun ToolRow(
    tool: Tool,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val ui = TOOL_UI[tool.name]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (ui != null) {
            Icon(
                imageVector = ui.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (ui != null) stringResource(ui.titleRes) else tool.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ui != null) stringResource(ui.descRes) else tool.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tools_example_label, stringResource(ui.exampleRes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}
