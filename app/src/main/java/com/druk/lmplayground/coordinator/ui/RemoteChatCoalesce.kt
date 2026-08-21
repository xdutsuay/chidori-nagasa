package com.druk.lmplayground.coordinator.ui

import com.druk.lmplayground.coordinator.model.RemoteChatMessage

/**
 * Merge streamed remote-chat frames into bubbles for the list UI (KMA-127).
 *
 * WIRE_CONTRACT.md allows multiple `from_user=false` reply frames per turn.
 * Appending each as its own list item produces one-word bubbles with action
 * rows. Rules:
 * - Same [RemoteChatMessage.id] → replace in place (desktop growing one id).
 * - Consecutive assistant frames with different ids → append text onto the
 *   last assistant bubble, keeping the first frame's id as the LazyColumn key.
 * - A user echo always starts a new bubble.
 */
internal fun coalesceRemoteChat(
    existing: List<RemoteChatMessage>,
    incoming: RemoteChatMessage,
): List<RemoteChatMessage> {
    val sameId = existing.indexOfFirst { it.id == incoming.id }
    if (sameId >= 0) {
        return existing.toMutableList().also { it[sameId] = incoming }
    }
    if (!incoming.fromUser && existing.isNotEmpty()) {
        val last = existing.last()
        if (!last.fromUser) {
            return existing.dropLast(1) + last.copy(
                text = last.text + incoming.text,
                sentAtEpochMillis = incoming.sentAtEpochMillis,
            )
        }
    }
    return existing + incoming
}
