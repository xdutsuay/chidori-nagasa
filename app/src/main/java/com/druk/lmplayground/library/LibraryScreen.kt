package com.druk.lmplayground.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

/**
 * Library screen: the user adds files/folders that get indexed on-device so
 * chats can answer from them via the `library_search` tool. UI only — indexing
 * lives in [LibraryIndexer], storage in [LibraryStore].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sources: List<LibrarySource>,
    indexState: IndexState,
    onBack: () -> Unit,
    onAddFile: () -> Unit,
    onAddFolder: () -> Unit,
    onRemove: (LibrarySource) -> Unit,
    onReindexAll: () -> Unit,
) {
    var pendingRemove by remember { mutableStateOf<LibrarySource?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onReindexAll,
                        enabled = indexState !is IndexState.Indexing,
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.library_reindex_all),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            Text(
                text = stringResource(R.string.library_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onAddFile, enabled = indexState !is IndexState.Indexing) {
                    Text(stringResource(R.string.library_add_file))
                }
                FilledTonalButton(onClick = onAddFolder, enabled = indexState !is IndexState.Indexing) {
                    Text(stringResource(R.string.library_add_folder))
                }
            }

            IndexStateRow(indexState = indexState)

            if (sources.isEmpty()) {
                if (indexState !is IndexState.Indexing) {
                    LibraryEmptyState(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sources, key = { it.id }) { source ->
                        LibrarySourceRow(
                            source = source,
                            onRemove = { pendingRemove = source },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }

    pendingRemove?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.library_remove_confirm_title)) },
            text = {
                Text(stringResource(R.string.library_remove_confirm_message, source.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onRemove(source)
                }) {
                    Text(stringResource(R.string.library_source_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Progress / result strip under the action buttons; nothing while Idle. */
@Composable
private fun IndexStateRow(indexState: IndexState) {
    when (val state = indexState) {
        is IndexState.Indexing -> {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = if (state.filesTotal != null) {
                        stringResource(
                            R.string.library_indexing_with_total,
                            state.sourceName,
                            state.filesDone,
                            state.filesTotal,
                        )
                    } else {
                        stringResource(
                            R.string.library_indexing_no_total,
                            state.sourceName,
                            state.filesDone,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                val total = state.filesTotal
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { state.filesDone.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        is IndexState.Done -> Text(
            text = stringResource(R.string.library_index_done, state.files, state.skipped),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        is IndexState.Failed -> Text(
            text = stringResource(R.string.library_index_failed, state.message),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        IndexState.Idle -> Unit
    }
}

@Composable
private fun LibraryEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibrarySourceRow(
    source: LibrarySource,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (source.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.library_source_summary,
                    source.documentCount,
                    source.documentCount,
                    source.chunkCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.library_source_remove))
        }
    }
}
