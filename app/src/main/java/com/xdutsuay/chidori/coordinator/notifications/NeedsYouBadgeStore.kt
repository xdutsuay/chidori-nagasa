package com.xdutsuay.chidori.coordinator.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide count of desktop runs in needs_you for the hub drawer badge.
 * Updated by the notification poller / inbox refresh — not a pairing change.
 */
object NeedsYouBadgeStore {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun setCount(count: Int) {
        _count.value = count.coerceAtLeast(0)
    }

    fun bump(by: Int = 1) {
        if (by <= 0) return
        _count.value = (_count.value + by).coerceAtLeast(0)
    }
}
