package com.druk.lmplayground.computer.activity

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.druk.lmplayground.R
import java.text.SimpleDateFormat
import java.util.Locale

private const val THUMBNAIL_HEIGHT_DP = 120
private const val SUMMARY_MAX_LINES = 2
private const val DETAIL_MAX_LINES = 2
private const val THUMBNAIL_CORNER_DP = 10

/**
 * Activity tab content: a chronological log of the AI agent's actions on the
 * Computer (COS), newest first, with an optional [header] slot (the desktop
 * screenshot views shown above the log). Reads the process-wide
 * [ComputerActivity.log]; `Clear` empties both the log and stored thumbnails.
 */
@Composable
fun ActivityPanel(
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    log: ComputerActivityLog = ComputerActivity.log,
) {
    val entries by log.entries.collectAsStateWithLifecycle()
    var viewingImageId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        if (entries.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = ComputerActivity::clear) {
                    Text(stringResource(R.string.cos_activity_clear))
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.cos_activity_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (header != null) item(key = "header") { header() }
            items(entries, key = { it.id }) { entry ->
                ActivityEntryRow(
                    entry = entry,
                    onThumbnailClick = { viewingImageId = entry.id },
                )
            }
        }
    }

    viewingImageId?.let { id ->
        val bytes = ComputerActivity.imageFor(id)
        if (bytes != null) {
            Dialog(onDismissRequest = { viewingImageId = null }) {
                val bitmap = remember(id) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cos_activity_screenshot_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityEntryRow(entry: ActivityEntry, onThumbnailClick: () -> Unit) {
    val timePattern = stringResource(R.string.cos_activity_time_pattern)
    val timeFormat = remember(timePattern) { SimpleDateFormat(timePattern, Locale.getDefault()) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = kindLabel(entry.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeFormat.format(java.util.Date(entry.atMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.summary.isNotBlank()) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = SUMMARY_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = outcomeLabel(entry.outcome),
                    style = MaterialTheme.typography.labelSmall,
                    color = outcomeColor(entry.outcome),
                )
                entry.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = DETAIL_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.kind == Kind.SCREENSHOT) {
                ScreenshotThumbnail(entryId = entry.id, onClick = onThumbnailClick)
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(entryId: Long, onClick: () -> Unit) {
    val bytes = ComputerActivity.imageFor(entryId) ?: return
    val bitmap = remember(entryId) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.cos_activity_screenshot_cd),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(THUMBNAIL_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(THUMBNAIL_CORNER_DP.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun kindLabel(kind: Kind): String = when (kind) {
    Kind.COMMAND -> stringResource(R.string.cos_activity_kind_command)
    Kind.SCREENSHOT -> stringResource(R.string.cos_activity_kind_screenshot)
    Kind.GUI -> stringResource(R.string.cos_activity_kind_gui)
}

@Composable
private fun outcomeLabel(outcome: Outcome): String = when (outcome) {
    Outcome.OK -> stringResource(R.string.cos_activity_outcome_ok)
    Outcome.ERROR -> stringResource(R.string.cos_activity_outcome_failed)
    Outcome.DENIED -> stringResource(R.string.cos_activity_outcome_denied)
    Outcome.CANCELLED -> stringResource(R.string.cos_activity_outcome_cancelled)
}

@Composable
private fun outcomeColor(outcome: Outcome) = when (outcome) {
    Outcome.OK -> MaterialTheme.colorScheme.primary
    Outcome.ERROR, Outcome.DENIED -> MaterialTheme.colorScheme.error
    Outcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
}
