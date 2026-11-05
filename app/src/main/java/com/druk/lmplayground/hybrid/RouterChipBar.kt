package com.druk.lmplayground.hybrid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Composer router chips: Local · Desktop · API (CP10).
 * Presentation only — caller persists [selected] and applies [HybridRouter.resolve].
 */
@Composable
fun RouterChipBar(
    selected: TurnRoute,
    onSelected: (TurnRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(TAG_ROUTER_CHIP_BAR),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (route in TurnRoute.entries) {
            FilterChip(
                selected = selected == route,
                onClick = { onSelected(route) },
                label = { Text(chipLabel(route)) },
                modifier = Modifier.testTag("$TAG_ROUTER_CHIP:${route.name}"),
            )
        }
    }
}

fun chipLabel(route: TurnRoute): String = when (route) {
    TurnRoute.LOCAL -> "Local"
    TurnRoute.DESKTOP -> "Desktop"
    TurnRoute.API -> "API"
}

const val TAG_ROUTER_CHIP_BAR = "router_chip_bar"
const val TAG_ROUTER_CHIP = "router_chip"
