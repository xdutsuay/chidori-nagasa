package com.druk.lmplayground.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Compact status chips for the CP8 team room (Planner / Worker / Critic).
 * Pure presentation — [session] owns the state.
 */
@Composable
fun TeamRoomBar(
    session: TeamRoomSession,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(TAG_TEAM_ROOM_BAR),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (role in session.roles()) {
            val status = session.statusOf(role)
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = "${role.mentionName} · ${statusLabel(status)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.testTag("$TAG_TEAM_ROLE_CHIP:${role.name}"),
            )
        }
    }
}

private fun statusLabel(status: TeamRoleStatus): String = when (status) {
    TeamRoleStatus.IDLE -> "idle"
    TeamRoleStatus.THINKING -> "thinking"
    TeamRoleStatus.TOOL -> "tool"
    TeamRoleStatus.WAITING_ON_YOU -> "needs you"
}

const val TAG_TEAM_ROOM_BAR = "team_room_bar"
const val TAG_TEAM_ROLE_CHIP = "team_role_chip"
