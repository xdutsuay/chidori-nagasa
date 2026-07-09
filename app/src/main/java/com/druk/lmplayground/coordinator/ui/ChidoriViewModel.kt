package com.druk.lmplayground.coordinator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backs the Settings -> Chidori Desktop screen (PRD.md §6.2/§6.3, v1
 * client-mode only — no run-triggering, no chat yet; see ROADMAP.md
 * Phase 2/3 for what's still to come here).
 *
 * Talks only to [App.coordinatorRepository] (CHIDORI_PROTOCOL.md §3.4 —
 * this is the ViewModel layer, so it's the appropriate place to hold that
 * single reference; it must not reach into `discovery`/`pairing`/
 * `transport` types directly).
 */
class ChidoriViewModel(app: Application) : AndroidViewModel(app) {

    private val coordinatorRepository = (app as App).coordinatorRepository

    private val _discoveredInstances = MutableStateFlow<List<DiscoveredInstance>>(emptyList())
    val discoveredInstances: StateFlow<List<DiscoveredInstance>> = _discoveredInstances.asStateFlow()

    private val _pairedInstances = MutableStateFlow<List<PairedInstance>>(emptyList())
    val pairedInstances: StateFlow<List<PairedInstance>> = _pairedInstances.asStateFlow()

    private val _manualHost = MutableStateFlow("")
    val manualHost: StateFlow<String> = _manualHost.asStateFlow()

    private val _manualPort = MutableStateFlow("")
    val manualPort: StateFlow<String> = _manualPort.asStateFlow()

    private val _pairingInProgressFor = MutableStateFlow<InstanceId?>(null)
    val pairingInProgressFor: StateFlow<InstanceId?> = _pairingInProgressFor.asStateFlow()

    private val _lastPairingError = MutableStateFlow<String?>(null)
    val lastPairingError: StateFlow<String?> = _lastPairingError.asStateFlow()

    // --- Coordinator monitor (PRD.md §6.3) ---------------------------------
    // Held here rather than in a separate ViewModel so the same instance backs
    // both the phone (ChidoriFragment) and tablet (ChidoriDetailContent) paths,
    // and so the monitor renders in place inside the Chidori screen without a
    // second nav destination. Read-only per protocol §2.4's v1 scope.

    private val _monitoredInstance = MutableStateFlow<PairedInstance?>(null)
    val monitoredInstance: StateFlow<PairedInstance?> = _monitoredInstance.asStateFlow()

    private val _monitorStatus = MutableStateFlow<CoordinatorStatus?>(null)
    val monitorStatus: StateFlow<CoordinatorStatus?> = _monitorStatus.asStateFlow()

    private val _monitorRuns = MutableStateFlow<List<AgentRunSummary>>(emptyList())
    val monitorRuns: StateFlow<List<AgentRunSummary>> = _monitorRuns.asStateFlow()

    private val _monitorRunDetail = MutableStateFlow<AgentRunDetail?>(null)
    val monitorRunDetail: StateFlow<AgentRunDetail?> = _monitorRunDetail.asStateFlow()

    private var monitorJob: Job? = null

    init {
        viewModelScope.launch {
            coordinatorRepository.observeDiscoveredInstances()
                .collect { _discoveredInstances.value = it }
        }
        viewModelScope.launch {
            coordinatorRepository.observePairedInstances()
                .collect { _pairedInstances.value = it }
        }
        coordinatorRepository.startDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        coordinatorRepository.stopDiscovery()
    }

    fun onManualHostChanged(value: String) {
        _manualHost.value = value
    }

    fun onManualPortChanged(value: String) {
        // Keep it digits-only; a malformed port is a UI-layer concern, not
        // something to let reach CoordinatorApi.
        _manualPort.value = value.filter { it.isDigit() }
    }

    fun beginPairing(instance: DiscoveredInstance) {
        _pairingInProgressFor.value = instance.instanceId
        _lastPairingError.value = null
        viewModelScope.launch {
            val state = coordinatorRepository.pairingManager.beginPairing(instance)
            if (state != PairingState.PAIRING_IN_PROGRESS) {
                _lastPairingError.value = "Could not reach ${instance.displayName}. Check it's on the same network."
                _pairingInProgressFor.value = null
            }
        }
    }

    fun confirmPairingCode(instanceId: InstanceId, code: String) {
        viewModelScope.launch {
            val state = coordinatorRepository.pairingManager.confirmPairingCode(instanceId, code)
            _pairingInProgressFor.value = null
            if (state != PairingState.PAIRED) {
                _lastPairingError.value = "Pairing code didn't match. Try again from the desktop app."
            }
        }
    }

    fun unpair(instanceId: InstanceId) {
        viewModelScope.launch {
            coordinatorRepository.pairingManager.unpair(instanceId)
        }
    }

    fun dismissError() {
        _lastPairingError.value = null
    }

    /**
     * Open the status/run monitor for [instance] and start polling. WIRE_CONTRACT.md's
     * v1 draft has no status/run push socket (only remote chat gets one, Phase 3), so
     * this polls on [POLL_INTERVAL_MILLIS] while the monitor is open. Re-selecting the
     * already-monitored instance is a no-op so the poll loop isn't restarted.
     */
    fun openMonitor(instance: PairedInstance) {
        if (_monitoredInstance.value?.instanceId == instance.instanceId) return
        _monitoredInstance.value = instance
        _monitorStatus.value = null
        _monitorRuns.value = emptyList()
        _monitorRunDetail.value = null
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (isActive) {
                runCatching { _monitorStatus.value = coordinatorRepository.getStatus(instance.instanceId) }
                runCatching { _monitorRuns.value = coordinatorRepository.listRuns(instance.instanceId) }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    fun closeMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        _monitoredInstance.value = null
        _monitorStatus.value = null
        _monitorRuns.value = emptyList()
        _monitorRunDetail.value = null
    }

    fun openRunDetail(runId: String) {
        val instanceId = _monitoredInstance.value?.instanceId ?: return
        viewModelScope.launch {
            runCatching { _monitorRunDetail.value = coordinatorRepository.getRunDetail(instanceId, runId) }
        }
    }

    fun dismissRunDetail() {
        _monitorRunDetail.value = null
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3000L
    }
}
