package com.druk.lmplayground.coordinator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.notifications.NeedsYouBadgeStore
import com.druk.lmplayground.coordinator.notifications.NeedsYouDeepLink
import com.druk.lmplayground.coordinator.notifications.NeedsYouPendingHold
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NeedsYouInboxItem(
    val instanceId: String,
    val displayName: String,
    val run: AgentRunSummary,
)

/**
 * Lists needs_you runs across paired desktops for the hub inbox.
 * Read-only listRuns polling — does not change pairing.
 */
class NeedsYouInboxViewModel(app: Application) : AndroidViewModel(app) {

    private val coordinator = (app as App).coordinatorRepository

    private val _items = MutableStateFlow<List<NeedsYouInboxItem>>(emptyList())
    val items: StateFlow<List<NeedsYouInboxItem>> = _items.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var pollJob: Job? = null
    private var paired: List<PairedInstance> = emptyList()

    init {
        viewModelScope.launch {
            coordinator.observePairedInstances().collect { list ->
                paired = list
                refreshOnce()
            }
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                refreshOnce()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    fun openItem(item: NeedsYouInboxItem) {
        NeedsYouPendingHold.offer(
            NeedsYouDeepLink(instanceId = item.instanceId, runId = item.run.runId),
        )
    }

    private suspend fun refreshOnce() {
        if (paired.isEmpty()) {
            _items.value = emptyList()
            NeedsYouBadgeStore.setCount(0)
            _statusMessage.value = null
            return
        }
        _loading.value = true
        val collected = mutableListOf<NeedsYouInboxItem>()
        var lastError: String? = null
        for (instance in paired) {
            runCatching {
                coordinator.listRuns(instance.instanceId)
            }.onSuccess { runs ->
                runs.filter { it.state == AgentRunState.NEEDS_YOU }.forEach { run ->
                    collected += NeedsYouInboxItem(
                        instanceId = instance.instanceId.value,
                        displayName = instance.displayName,
                        run = run,
                    )
                }
            }.onFailure { e ->
                lastError = e.message
            }
        }
        _items.value = collected
        NeedsYouBadgeStore.setCount(collected.size)
        _statusMessage.value = if (collected.isEmpty() && lastError != null) lastError else null
        _loading.value = false
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val POLL_MS = 15_000L
    }
}
