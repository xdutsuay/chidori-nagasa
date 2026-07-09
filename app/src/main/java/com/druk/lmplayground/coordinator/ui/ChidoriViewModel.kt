package com.druk.lmplayground.coordinator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}
