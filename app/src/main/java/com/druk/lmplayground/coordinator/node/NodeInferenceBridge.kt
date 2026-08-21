package com.druk.lmplayground.coordinator.node

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Narrow callback so `net/coordinator` can offer node mode without a
 * compile-time dependency on `inference/` / ModelRuntime (protocol §3.4).
 * Wired from conversation UI when a model is loaded.
 */
data class NodeOfferedModel(
    val id: String,
    val displayName: String,
)

data class NodeChatMessage(
    val role: String,
    val content: String,
)

interface NodeInferenceBridge {
    fun currentModel(): NodeOfferedModel?

    /** One-shot completion for desktop-routed Ask. Fail if busy / unloaded. */
    suspend fun complete(messages: List<NodeChatMessage>): Result<String>
}

object AbsentNodeInferenceBridge : NodeInferenceBridge {
    override fun currentModel(): NodeOfferedModel? = null

    override suspend fun complete(messages: List<NodeChatMessage>): Result<String> =
        Result.failure(IllegalStateException("No on-device model loaded"))
}

/**
 * Process-wide slot filled by ConversationViewModel when a model is ready.
 * Node registration reads this; do not put ModelRuntime types here.
 */
object NodeInferenceHub {
    private val _hasLoadedModel = MutableStateFlow(false)
    /** True when [bridge] reports a loaded on-device model (gates the node-offer toggle). */
    val hasLoadedModel: StateFlow<Boolean> = _hasLoadedModel.asStateFlow()

    @Volatile
    var bridge: NodeInferenceBridge = AbsentNodeInferenceBridge
        set(value) {
            field = value
            _hasLoadedModel.value = value.currentModel() != null
        }

    fun clear() {
        bridge = AbsentNodeInferenceBridge
    }
}
