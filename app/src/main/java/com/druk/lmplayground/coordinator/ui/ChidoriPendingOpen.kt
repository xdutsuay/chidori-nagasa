package com.druk.lmplayground.coordinator.ui

/**
 * One-shot flags for hub "Ask desktop" — open monitor chat after navigating
 * to Chidori Desktop without changing pairing.
 */
object ChidoriPendingOpen {
    @Volatile
    var openRemoteChat: Boolean = false

    fun takeOpenRemoteChat(): Boolean {
        val v = openRemoteChat
        openRemoteChat = false
        return v
    }
}
