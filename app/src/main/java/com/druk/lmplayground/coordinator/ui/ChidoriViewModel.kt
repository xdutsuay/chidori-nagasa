package com.druk.lmplayground.coordinator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.PairedInstance
import com.druk.lmplayground.coordinator.model.PairingState
import com.druk.lmplayground.coordinator.model.ProtocolVersion
import com.druk.lmplayground.coordinator.model.RemoteChatMessage
import com.druk.lmplayground.coordinator.node.NodeInferenceHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Backs the Settings -> Chidori Desktop screen (PRD.md §6.2/§6.3/§6.4,
 * v1 client-mode only — no run-triggering or node mode; see ROADMAP.md).
 *
 * Talks only to [App.coordinatorRepository] (CHIDORI_PROTOCOL.md §3.4 —
 * this is the ViewModel layer, so it's the appropriate place to hold that
 * single reference; it must not reach into `discovery`/`pairing`/
 * `transport` types directly).
 */
class ChidoriViewModel(
    app: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {

    private val coordinatorRepository = (app as App).coordinatorRepository

    private val _discoveredInstances = MutableStateFlow<List<DiscoveredInstance>>(emptyList())
    val discoveredInstances: StateFlow<List<DiscoveredInstance>> = _discoveredInstances.asStateFlow()

    private val _pairedInstances = MutableStateFlow<List<PairedInstance>>(emptyList())
    val pairedInstances: StateFlow<List<PairedInstance>> = _pairedInstances.asStateFlow()

    private val _manualHost = MutableStateFlow("")
    val manualHost: StateFlow<String> = _manualHost.asStateFlow()

    private val _manualPort = MutableStateFlow(ProtocolVersion.DEFAULT_COMPANION_PORT.toString())
    val manualPort: StateFlow<String> = _manualPort.asStateFlow()

    private val _pairingInProgressFor = MutableStateFlow<InstanceId?>(null)
    val pairingInProgressFor: StateFlow<InstanceId?> = _pairingInProgressFor.asStateFlow()

    // Set only once /pairing/begin has actually succeeded on the desktop —
    // the code-entry dialog keys off this, so a failed begin can't leave a
    // dialog open where any code the user types is guaranteed to 403
    // (previously the dialog opened optimistically on tap, before the begin
    // result was known).
    private val _pairingCodeEntryFor = MutableStateFlow<InstanceId?>(null)
    val pairingCodeEntryFor: StateFlow<InstanceId?> = _pairingCodeEntryFor.asStateFlow()

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

    private val _monitorStatusMessage = MutableStateFlow<String?>(null)
    val monitorStatusMessage: StateFlow<String?> = _monitorStatusMessage.asStateFlow()

    private val _monitorRuns = MutableStateFlow<List<AgentRunSummary>>(emptyList())
    val monitorRuns: StateFlow<List<AgentRunSummary>> = _monitorRuns.asStateFlow()

    private val _monitorRunDetail = MutableStateFlow<AgentRunDetail?>(null)
    val monitorRunDetail: StateFlow<AgentRunDetail?> = _monitorRunDetail.asStateFlow()

    private val _runInjectInput = MutableStateFlow("")
    val runInjectInput: StateFlow<String> = _runInjectInput.asStateFlow()

    private val _runControlInProgress = MutableStateFlow(false)
    val runControlInProgress: StateFlow<Boolean> = _runControlInProgress.asStateFlow()

    /** Current step text for RUNNING runs, keyed by runId (KMA-128). */
    private val _monitorRunningSteps = MutableStateFlow<Map<String, String>>(emptyMap())
    val monitorRunningSteps: StateFlow<Map<String, String>> = _monitorRunningSteps.asStateFlow()

    private val _monitorLastUpdatedEpochMillis = MutableStateFlow<Long?>(null)
    val monitorLastUpdatedEpochMillis: StateFlow<Long?> = _monitorLastUpdatedEpochMillis.asStateFlow()

    private var monitorJob: Job? = null
    private var detailRunId: String? = null
    private var consecutivePollFailures = 0

    init {
        viewModelScope.launch {
            coordinatorRepository.observeDiscoveredInstances()
                .collect { _discoveredInstances.value = it }
        }
        viewModelScope.launch {
            coordinatorRepository.observePairedInstances()
                .collect { list ->
                    _pairedInstances.value = list
                    restoreMonitoredInstanceIfNeeded(list)
                }
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
            val result = coordinatorRepository.pairingManager.beginPairing(instance)
            if (result.state == PairingState.PAIRING_IN_PROGRESS) {
                // Begin succeeded: the desktop is now showing a code — open
                // the entry dialog (collected as state by the screen).
                _pairingCodeEntryFor.value = instance.instanceId
            } else {
                _lastPairingError.value = buildString {
                    append("Could not reach ${instance.displayName}.")
                    if (result.errorDetail != null) {
                        append(' ')
                        append(result.errorDetail)
                    } else {
                        append(" Check it's on the same network.")
                    }
                }
                _pairingInProgressFor.value = null
            }
        }
    }

    /**
     * Manual host:port fallback (protocol §2.1 — required in v1, not
     * gated behind mDNS working). The real instance_id isn't knowable
     * before first contact, so pairing starts under a deterministic
     * placeholder; PairingManager re-keys the record to the server-asserted
     * id from `/pairing/confirm`. The code-entry dialog opens off
     * [pairingCodeEntryFor] once begin succeeds (same as discovered pairing).
     */
    fun beginManualPairing() {
        val host = _manualHost.value.trim()
        val port = _manualPort.value.toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return
        val placeholder = DiscoveredInstance(
            instanceId = InstanceId("manual:$host:$port"),
            displayName = "$host:$port",
            host = host,
            port = port,
            protocolVersion = ProtocolVersion.CURRENT,
            pairingRequired = true,
        )
        beginPairing(placeholder)
    }

    fun confirmPairingCode(instanceId: InstanceId, code: String) {
        viewModelScope.launch {
            val result = coordinatorRepository.pairingManager.confirmPairingCode(instanceId, code)
            _pairingInProgressFor.value = null
            _pairingCodeEntryFor.value = null
            if (result.state != PairingState.PAIRED) {
                _lastPairingError.value = result.errorDetail
                    ?: "Pairing code didn't match. Try again from the desktop app."
            }
        }
    }

    /** Closes the code-entry dialog without confirming (user dismissed it). */
    fun dismissPairingCodeEntry() {
        _pairingCodeEntryFor.value = null
        _pairingInProgressFor.value = null
    }

    fun unpair(instanceId: InstanceId) {
        viewModelScope.launch {
            coordinatorRepository.pairingManager.unpair(instanceId)
        }
    }

    fun dismissError() {
        _lastPairingError.value = null
    }

    // --- Node mode (protocol §2.5) ----------------------------------------
    private val _nodeOffering = MutableStateFlow(false)
    val nodeOffering: StateFlow<Boolean> = _nodeOffering.asStateFlow()

    private val _nodeOfferError = MutableStateFlow<String?>(null)
    val nodeOfferError: StateFlow<String?> = _nodeOfferError.asStateFlow()

    /**
     * Kill switch ([NodeRegistrationCapability.isSupported]) AND an on-device
     * model must be loaded before the offer toggle is shown (KMA-97).
     */
    val nodeOfferSupported: StateFlow<Boolean> = NodeInferenceHub.hasLoadedModel
        .map { hasModel -> coordinatorRepository.nodeRegistration.isSupported && hasModel }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            initialValue = coordinatorRepository.nodeRegistration.isSupported &&
                NodeInferenceHub.hasLoadedModel.value,
        )

    fun setNodeOffering(enabled: Boolean) {
        val instance = _monitoredInstance.value ?: return
        if (!_nodeOffering.value && !enabled) return
        if (_nodeOffering.value == enabled) return
        viewModelScope.launch {
            if (enabled) {
                _nodeOfferError.value = null
                coordinatorRepository.nodeRegistration.registerAsNode(instance.instanceId)
                    .onSuccess { _nodeOffering.value = true }
                    .onFailure {
                        _nodeOffering.value = false
                        _nodeOfferError.value = it.message ?: "Could not offer as node"
                    }
            } else {
                coordinatorRepository.nodeRegistration.unregisterAsNode(instance.instanceId)
                _nodeOffering.value = false
                _nodeOfferError.value = null
            }
        }
    }

    fun dismissNodeOfferError() {
        _nodeOfferError.value = null
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
        savedStateHandle[KEY_MONITORED_INSTANCE_ID] = instance.instanceId.value
        _monitorStatus.value = null
        _monitorStatusMessage.value = null
        _monitorRuns.value = emptyList()
        _monitorRunDetail.value = null
        _monitorRunningSteps.value = emptyMap()
        _monitorLastUpdatedEpochMillis.value = null
        detailRunId = null
        consecutivePollFailures = 0
        monitorJob?.cancel()
        monitorJob = viewModelScope.launch {
            while (isActive) {
                pollMonitorOnce(instance.instanceId)
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun pollMonitorOnce(instanceId: InstanceId) {
        val statusResult = runCatching { coordinatorRepository.getStatus(instanceId) }
        val runsResult = runCatching { coordinatorRepository.listRuns(instanceId) }
        when (
            val evaluation = evaluateMonitorPoll(
                consecutivePollFailures,
                statusResult,
                runsResult,
                POLL_FAILURES_BEFORE_DISCONNECT,
            )
        ) {
            is MonitorPollEvaluation.Unreachable -> {
                consecutivePollFailures = evaluation.consecutiveFailures
                if (evaluation.disconnected) {
                    _monitorStatus.value = CoordinatorStatus.DISCONNECTED
                }
                _monitorStatusMessage.value = evaluation.message
                return
            }
            is MonitorPollEvaluation.Connected -> {
                consecutivePollFailures = 0
                evaluation.status?.let { info ->
                    _monitorStatus.value = info.status
                    _monitorStatusMessage.value = evaluation.warningMessage ?: info.errorMessage
                }
                evaluation.runs?.let { runs ->
                    _monitorRuns.value = runs
                    val fromList = runningStepsFromRuns(runs)
                    _monitorRunningSteps.value = fromList
                    refreshRunningSteps(instanceId, runs.filter { it.currentStep.isNullOrBlank() })
                }
            }
        }
        _monitorLastUpdatedEpochMillis.value = System.currentTimeMillis()
        val openId = detailRunId
        if (openId != null) {
            runCatching {
                coordinatorRepository.getRunDetail(instanceId, openId)
            }.onSuccess { detail ->
                _monitorRunDetail.value = detail
                if (detail.summary.state == AgentRunState.RUNNING) {
                    detail.currentStep?.takeIf { it.isNotBlank() }?.let { step ->
                        _monitorRunningSteps.value =
                            _monitorRunningSteps.value + (openId to step)
                    }
                }
            }.onFailure { e ->
                _monitorStatusMessage.value = describePollThrowable(e)
            }
        }
    }

    private suspend fun refreshRunningSteps(instanceId: InstanceId, runs: List<AgentRunSummary>) {
        val running = runs.filter { it.state == AgentRunState.RUNNING }.take(MAX_RUNNING_STEP_FETCHES)
        if (running.isEmpty()) return
        val steps = _monitorRunningSteps.value.toMutableMap()
        for (run in running) {
            runCatching {
                coordinatorRepository.getRunDetail(instanceId, run.runId)
            }.onSuccess { detail ->
                val step = detail.currentStep?.takeIf { it.isNotBlank() } ?: return@onSuccess
                steps[run.runId] = step
            }.onFailure { e ->
                _monitorStatusMessage.value = describePollThrowable(e)
            }
        }
        _monitorRunningSteps.value = steps
    }

    fun closeMonitor() {
        val offered = _monitoredInstance.value
        if (_nodeOffering.value && offered != null) {
            viewModelScope.launch {
                coordinatorRepository.nodeRegistration.unregisterAsNode(offered.instanceId)
            }
            _nodeOffering.value = false
        }
        closeChat()
        monitorJob?.cancel()
        monitorJob = null
        detailRunId = null
        consecutivePollFailures = 0
        _monitoredInstance.value = null
        savedStateHandle.remove<String>(KEY_MONITORED_INSTANCE_ID)
        _monitorStatus.value = null
        _monitorStatusMessage.value = null
        _monitorRuns.value = emptyList()
        _monitorRunDetail.value = null
        _monitorRunningSteps.value = emptyMap()
        _monitorLastUpdatedEpochMillis.value = null
    }

    /**
     * After process death / config change, reopen the monitor for the last
     * selected paired instance so the chat entry stays reachable (KMA-98).
     */
    private fun restoreMonitoredInstanceIfNeeded(paired: List<PairedInstance>) {
        if (_monitoredInstance.value != null || paired.isEmpty()) return
        val savedId = savedStateHandle.get<String>(KEY_MONITORED_INSTANCE_ID)
        val match = if (savedId != null) {
            paired.firstOrNull { it.instanceId.value == savedId } ?: paired.first()
        } else {
            return
        }
        openMonitor(match)
    }

    fun openRunDetail(runId: String) {
        val instanceId = _monitoredInstance.value?.instanceId ?: return
        detailRunId = runId
        viewModelScope.launch {
            runCatching { coordinatorRepository.getRunDetail(instanceId, runId) }
                .onSuccess { _monitorRunDetail.value = it }
                .onFailure { e -> _monitorStatusMessage.value = describePollThrowable(e) }
        }
    }

    fun dismissRunDetail() {
        detailRunId = null
        _monitorRunDetail.value = null
        _runInjectInput.value = ""
    }

    fun onRunInjectInputChanged(value: String) {
        _runInjectInput.value = value
    }

    fun stopRun() {
        val instanceId = _monitoredInstance.value?.instanceId ?: return
        val runId = detailRunId ?: return
        if (_runControlInProgress.value) return
        viewModelScope.launch {
            _runControlInProgress.value = true
            runCatching { coordinatorRepository.cancelRun(instanceId, runId) }
                .onSuccess { refreshRunDetail(instanceId, runId) }
                .onFailure { e -> _monitorStatusMessage.value = describePollThrowable(e) }
            _runControlInProgress.value = false
        }
    }

    fun injectRunMessage() {
        val instanceId = _monitoredInstance.value?.instanceId ?: return
        val runId = detailRunId ?: return
        val text = _runInjectInput.value.trim()
        if (text.isEmpty() || _runControlInProgress.value) return
        viewModelScope.launch {
            _runControlInProgress.value = true
            runCatching { coordinatorRepository.injectRunMessage(instanceId, runId, text) }
                .onSuccess {
                    _runInjectInput.value = ""
                    refreshRunDetail(instanceId, runId)
                }
                .onFailure { e -> _monitorStatusMessage.value = describePollThrowable(e) }
            _runControlInProgress.value = false
        }
    }

    private suspend fun refreshRunDetail(instanceId: InstanceId, runId: String) {
        runCatching { coordinatorRepository.getRunDetail(instanceId, runId) }
            .onSuccess { _monitorRunDetail.value = it }
    }

    // --- Remote chat (PRD.md §6.4) -----------------------------------------
    // Routed through the monitored instance's coordinator; the surface must
    // stay clearly distinct from on-device chat (different privacy
    // properties — PRD §6.4/§7). Messages live in memory for the lifetime of
    // the open chat only; the desktop side owns durable history in v1.

    private val _chatOpen = MutableStateFlow(false)
    val chatOpen: StateFlow<Boolean> = _chatOpen.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<RemoteChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<RemoteChatMessage>> = _chatMessages.asStateFlow()

    private val _chatConnectionState =
        MutableStateFlow(CoordinatorConnectionState.DISCONNECTED)
    val chatConnectionState: StateFlow<CoordinatorConnectionState> = _chatConnectionState.asStateFlow()

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    private val _chatAwaitingReply = MutableStateFlow(false)
    val chatAwaitingReply: StateFlow<Boolean> = _chatAwaitingReply.asStateFlow()

    private var chatStreamJob: Job? = null
    private var chatConnectionJob: Job? = null

    fun onChatInputChanged(value: String) {
        _chatInput.value = value
    }

    /** Opens the chat surface for the currently monitored instance. */
    fun openChat() {
        val instance = _monitoredInstance.value ?: return
        if (_chatOpen.value) return
        _chatOpen.value = true
        _chatMessages.value = emptyList()
        _chatAwaitingReply.value = false
        chatConnectionJob = viewModelScope.launch {
            coordinatorRepository.observeConnectionState(instance.instanceId)
                .collect { _chatConnectionState.value = it }
        }
        chatStreamJob = viewModelScope.launch {
            // The flow completes when the socket drops (see CoordinatorApi);
            // leave the surface open showing DISCONNECTED rather than
            // auto-closing — PRD §6.4's graceful-degradation requirement.
            runCatching {
                coordinatorRepository.observeRemoteChat(instance.instanceId)
                    .collect { message ->
                        _chatMessages.value = coalesceRemoteChat(_chatMessages.value, message)
                        if (!message.fromUser) _chatAwaitingReply.value = false
                    }
            }
        }
    }

    fun closeChat() {
        chatStreamJob?.cancel()
        chatStreamJob = null
        chatConnectionJob?.cancel()
        chatConnectionJob = null
        _chatOpen.value = false
        _chatMessages.value = emptyList()
        _chatConnectionState.value = CoordinatorConnectionState.DISCONNECTED
        _chatInput.value = ""
        _chatAwaitingReply.value = false
    }

    /**
     * Sends the current input. Cleared only on a successful handoff to the
     * socket — on failure the draft stays in the input and the connection
     * banner tells the user why (never silently drop a message, PRD §6.4).
     */
    fun sendChatMessage() {
        val instance = _monitoredInstance.value ?: return
        val text = _chatInput.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            runCatching { coordinatorRepository.sendRemoteChatMessage(instance.instanceId, text) }
                .onSuccess {
                    _chatInput.value = ""
                    _chatAwaitingReply.value = true
                }
                .onFailure {
                    _chatConnectionState.value = CoordinatorConnectionState.DISCONNECTED
                    _chatAwaitingReply.value = false
                }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3000L
        const val POLL_FAILURES_BEFORE_DISCONNECT = 2
        const val MAX_RUNNING_STEP_FETCHES = 3
        const val KEY_MONITORED_INSTANCE_ID = "monitored-instance-id"
    }
}
