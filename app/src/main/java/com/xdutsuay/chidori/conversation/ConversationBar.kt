@file:OptIn(ExperimentalMaterial3Api::class)

package com.xdutsuay.chidori.conversation

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xdutsuay.chidori.R
import com.xdutsuay.chidori.AppBar
import com.xdutsuay.chidori.models.ModelInfo
import com.xdutsuay.chidori.theme.PlaygroundTheme
import java.time.LocalDate

const val ConversationBarTestTag = "ConversationBarTestTag"

@Composable
fun ConversationBar(
    modelInfo: ModelInfo?,
    modelStatus: String? = null,
    modifier: Modifier = Modifier,
    colors: TopAppBarColors? = null,
    showNavIcon: Boolean = true,
    /**
     * Compact mode collapses the model name + status onto a single line so the
     * bar feels less heavy on tablet landscape, where vertical space is the
     * constrained dimension. The bar's own height stays at the Material 3
     * default (64dp), but the title content drops from ~50dp to ~28dp tall.
     */
    compact: Boolean = false,
    /** Short Computer (COS) state shown under the model name; null hides it. */
    computerStatus: String? = null,
    onModelNamePressed: () -> Unit = { },
    onNavIconPressed: () -> Unit = { },
    onNewSessionPressed: () -> Unit = { }
) {
    AppBar(
        modifier = modifier.testTag(ConversationBarTestTag),
        colors = colors,
        showNavIcon = showNavIcon,
        // Compact bar: shrink from M3 default 64dp to 40dp on tablet
        // landscape. The title content is a single line of titleMedium so it
        // fits; the trailing IconButton stays at its 40dp minimum tap target.
        expandedHeight = if (compact) 40.dp else Dp.Unspecified,
        onNavIconPressed = onNavIconPressed,
        title = {
            if (modelInfo != null) {
                Surface(
                    onClick = onModelNamePressed,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                ) {
                    if (compact) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = modelInfo.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            val status = listOfNotNull(
                                (modelStatus ?: modelInfo.description).takeIf { it.isNotBlank() },
                                computerStatus,
                            ).joinToString("  ·  ")
                            if (status.isNotBlank()) {
                                Text(
                                    text = "  ·  $status",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ArrowDropDown,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = null
                            )
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = modelInfo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    contentDescription = null
                                )
                            }
                            Text(
                                text = listOfNotNull(
                                    (modelStatus ?: modelInfo.description).takeIf { it.isNotBlank() },
                                    computerStatus,
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                        }
                    }
                }
            } else {
                Surface(
                    onClick = onModelNamePressed,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = if (computerStatus == null || compact) 8.dp else 4.dp,
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.select_model),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                imageVector = Icons.Outlined.ArrowDropDown,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                contentDescription = null
                            )
                        }
                        if (computerStatus != null && !compact) {
                            Text(
                                text = computerStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onNewSessionPressed) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = stringResource(R.string.new_conversation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Preview
@Composable
fun ChannelBarPreview() {
    PlaygroundTheme {
        ConversationBar(modelInfo = null)
    }
}

@Preview("Bar with model info")
@Composable
fun ChannelBarWithModelInfoPreview() {
    PlaygroundTheme {
        ConversationBar(
            modelInfo = ModelInfo(
                name = "Model Name Model Name Model Name Model Name Model Name",
                filename = "model.gguf",
                remoteUri = Uri.parse("https://example.com/model.gguf"),
                releaseDate = LocalDate.parse("2025-01-01"),
                description = "Model Description"
            ),
            modelStatus = "Loading 50%"
        )
    }
}
