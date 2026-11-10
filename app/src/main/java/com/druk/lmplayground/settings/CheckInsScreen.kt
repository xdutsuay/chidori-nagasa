@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.checkin.CheckInFrequency
import com.druk.lmplayground.checkin.CheckInPreferences
import com.druk.lmplayground.checkin.OpenItemEntity
import com.druk.lmplayground.checkin.OpenItemStatus

/**
 * Settings → Check-ins. Phone gets the scaffold; the tablet detail pane
 * uses [CheckInsContent] alone.
 */
@Composable
fun CheckInsScreen(
    enabled: Boolean,
    frequency: CheckInFrequency,
    inactivityMs: Long,
    quietStartMinute: Int,
    quietEndMinute: Int,
    paused: Boolean,
    items: List<OpenItemEntity>,
    onEnabled: (Boolean) -> Unit,
    onFrequency: (CheckInFrequency) -> Unit,
    onInactivity: (Long) -> Unit,
    onQuietStart: (Int) -> Unit,
    onQuietEnd: (Int) -> Unit,
    onPaused: (Boolean) -> Unit,
    onPark: (OpenItemEntity) -> Unit,
    onClose: (OpenItemEntity) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.check_ins)) },
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
        CheckInsContent(
            enabled = enabled,
            frequency = frequency,
            inactivityMs = inactivityMs,
            quietStartMinute = quietStartMinute,
            quietEndMinute = quietEndMinute,
            paused = paused,
            items = items,
            onEnabled = onEnabled,
            onFrequency = onFrequency,
            onInactivity = onInactivity,
            onQuietStart = onQuietStart,
            onQuietEnd = onQuietEnd,
            onPaused = onPaused,
            onPark = onPark,
            onClose = onClose,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
fun CheckInsContent(
    enabled: Boolean,
    frequency: CheckInFrequency,
    inactivityMs: Long,
    quietStartMinute: Int,
    quietEndMinute: Int,
    paused: Boolean,
    items: List<OpenItemEntity>,
    onEnabled: (Boolean) -> Unit,
    onFrequency: (CheckInFrequency) -> Unit,
    onInactivity: (Long) -> Unit,
    onQuietStart: (Int) -> Unit,
    onQuietEnd: (Int) -> Unit,
    onPaused: (Boolean) -> Unit,
    onPark: (OpenItemEntity) -> Unit,
    onClose: (OpenItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.check_ins_blurb), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.check_ins_enable), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.check_ins_enable_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.testTag("checkins_enabled"),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.check_ins_frequency), style = MaterialTheme.typography.titleMedium)
        Row {
            CheckInFrequency.entries.forEach { option ->
                TextButton(
                    onClick = { onFrequency(option) },
                    modifier = Modifier.testTag("checkins_frequency_${option.name}"),
                ) {
                    Text(
                        frequencyLabel(option),
                        color = if (option == frequency) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.check_ins_inactivity), style = MaterialTheme.typography.titleMedium)
        Row {
            CheckInPreferences.INACTIVITY_OPTIONS_MS.forEach { option ->
                TextButton(onClick = { onInactivity(option) }) {
                    Text(
                        inactivityLabel(option),
                        color = if (option == inactivityMs) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.check_ins_quiet_hours), style = MaterialTheme.typography.titleMedium)
        QuietRow(
            label = stringResource(R.string.check_ins_quiet_start),
            minute = quietStartMinute,
            onChange = onQuietStart,
            tag = "checkins_quiet_start",
        )
        QuietRow(
            label = stringResource(R.string.check_ins_quiet_end),
            minute = quietEndMinute,
            onChange = onQuietEnd,
            tag = "checkins_quiet_end",
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pause_background_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.pause_background_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = paused,
                onCheckedChange = onPaused,
                modifier = Modifier.testTag("checkins_pause"),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.check_ins_open_items),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("checkins_open_items"),
        )
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.check_ins_open_items_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .testTag("open_item_row_${item.id}"),
            ) {
                Text(item.summary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${item.source} · ${item.kind} · ${item.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    if (item.status != OpenItemStatus.PARKED.name) {
                        TextButton(
                            onClick = { onPark(item) },
                            modifier = Modifier.testTag("open_item_park_${item.id}"),
                        ) {
                            Text(stringResource(R.string.check_in_action_park))
                        }
                    }
                    TextButton(
                        onClick = { onClose(item) },
                        modifier = Modifier.testTag("open_item_close_${item.id}"),
                    ) {
                        Text(stringResource(R.string.check_in_action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuietRow(label: String, minute: Int, onChange: (Int) -> Unit, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(tag)) {
        Text(label, modifier = Modifier.padding(end = 8.dp))
        Text(formatMinute(minute), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onChange((minute + CheckInPreferences.MINUTES_PER_DAY - 60) % CheckInPreferences.MINUTES_PER_DAY) }) {
            Text(stringResource(R.string.check_ins_hour_minus))
        }
        TextButton(onClick = { onChange((minute + 60) % CheckInPreferences.MINUTES_PER_DAY) }) {
            Text(stringResource(R.string.check_ins_hour_plus))
        }
    }
}

@Composable
private fun frequencyLabel(frequency: CheckInFrequency): String = when (frequency) {
    CheckInFrequency.SIX_HOURS -> stringResource(R.string.check_ins_freq_6h)
    CheckInFrequency.TWELVE_HOURS -> stringResource(R.string.check_ins_freq_12h)
    CheckInFrequency.DAILY -> stringResource(R.string.check_ins_freq_daily)
    CheckInFrequency.WEEKLY -> stringResource(R.string.check_ins_freq_weekly)
}

@Composable
private fun inactivityLabel(ms: Long): String = when (ms) {
    30L * 60L * 1000L -> stringResource(R.string.check_ins_inactive_30m)
    60L * 60L * 1000L -> stringResource(R.string.check_ins_inactive_1h)
    6L * 60L * 60L * 1000L -> stringResource(R.string.check_ins_inactive_6h)
    else -> stringResource(R.string.check_ins_inactive_2h)
}

private fun formatMinute(minute: Int): String {
    val hour = minute / 60
    val min = minute % 60
    return "%02d:%02d".format(hour, min)
}
