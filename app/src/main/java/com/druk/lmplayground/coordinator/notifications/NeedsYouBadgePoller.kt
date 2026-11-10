package com.druk.lmplayground.coordinator.notifications

import com.druk.lmplayground.coordinator.CoordinatorRepository
import com.druk.lmplayground.coordinator.model.AgentRunState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground poll of paired desktops for needs_you runs so the hub badge /
 * home banner stay fresh without opening the monitor (pairing untouched).
 *
 * [isPaused] is the app-wide background pause (Settings → Check-ins). While
 * it is on, this poller does not call listRuns. Future routines must read
 * the same switch.
 */
class NeedsYouBadgePoller(
    private val coordinator: CoordinatorRepository,
    private val scope: CoroutineScope,
    private val isAppInForeground: () -> Boolean,
    private val isPaused: () -> Boolean = { false },
    private val pollMs: Long = DEFAULT_POLL_MS,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            coordinator.observePairedInstances().collectLatest { paired ->
                while (isActive) {
                    if (!isAppInForeground() || isPaused()) {
                        delay(pollMs)
                        continue
                    }
                    if (paired.isEmpty()) {
                        NeedsYouBadgeStore.setCount(0)
                    } else {
                        var count = 0
                        for (instance in paired) {
                            runCatching {
                                coordinator.listRuns(instance.instanceId)
                            }.onSuccess { runs ->
                                count += runs.count { it.state == AgentRunState.NEEDS_YOU }
                            }
                        }
                        NeedsYouBadgeStore.setCount(count)
                    }
                    delay(pollMs)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_POLL_MS = 20_000L
    }
}
