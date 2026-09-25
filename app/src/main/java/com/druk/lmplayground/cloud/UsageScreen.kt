package com.druk.lmplayground.cloud

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.lmplayground.data.UsageAggregator
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    state: UsageUiState,
    onRangeSelected: (UsageRange) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row {
                for (range in UsageRange.entries) {
                    FilterChip(
                        selected = state.range == range,
                        onClick = { onRangeSelected(range) },
                        label = { Text(range.label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.usage_requests, state.totals.requests))
            Text(stringResource(R.string.usage_tokens_in, state.totals.promptTokens))
            Text(stringResource(R.string.usage_tokens_out, state.totals.completionTokens))
            Text(
                if (state.totals.costMicros != null) {
                    stringResource(
                        R.string.usage_cost_known,
                        String.format(Locale.US, "%.4f", state.totals.costMicros / 1_000_000.0),
                    )
                } else {
                    stringResource(R.string.usage_cost_unknown)
                },
            )
            Text(
                stringResource(R.string.usage_cost_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.usage_per_day), style = MaterialTheme.typography.titleMedium)
            RequestsBarChart(state.perDay, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.usage_by_route), style = MaterialTheme.typography.titleMedium)
            BreakdownTable(state.byRoute)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.usage_by_model), style = MaterialTheme.typography.titleMedium)
            BreakdownTable(state.byModel)
        }
    }
}

@Composable
private fun BreakdownTable(rows: List<UsageAggregator.BreakdownRow>) {
    if (rows.isEmpty()) {
        Text(
            stringResource(R.string.usage_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    for (row in rows) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(row.key, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.usage_breakdown_line,
                    row.requests,
                    row.promptTokens,
                    row.completionTokens,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.costMicros != null) {
                Text(
                    stringResource(
                        R.string.usage_cost_known,
                        String.format(Locale.US, "%.4f", row.costMicros / 1_000_000.0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RequestsBarChart(
    days: List<UsageAggregator.DayBucket>,
    barColor: Color,
) {
    val max = (days.maxOfOrNull { it.requests } ?: 0).coerceAtLeast(1)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 8.dp),
    ) {
        if (days.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val barWidth = ((size.width - gap * (days.size - 1)) / days.size).coerceAtLeast(2f)
        days.forEachIndexed { index, day ->
            val h = (day.requests.toFloat() / max) * size.height
            val left = index * (barWidth + gap)
            drawRect(
                color = barColor,
                topLeft = Offset(left, size.height - h),
                size = Size(barWidth, h),
            )
        }
    }
}
