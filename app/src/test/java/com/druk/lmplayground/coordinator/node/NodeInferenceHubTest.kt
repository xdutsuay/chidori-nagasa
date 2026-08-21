package com.druk.lmplayground.coordinator.node

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeInferenceHubTest {

    @After
    fun tearDown() {
        NodeInferenceHub.clear()
    }

    @Test
    fun `setting bridge with a model marks model as loaded`() {
        val bridge = FakeBridge(NodeOfferedModel("model-id", "Model"))

        NodeInferenceHub.bridge = bridge

        assertSame(bridge, NodeInferenceHub.bridge)
        assertTrue(NodeInferenceHub.hasLoadedModel.value)
    }

    @Test
    fun `setting bridge without a model leaves model unloaded`() {
        NodeInferenceHub.bridge = FakeBridge(null)

        assertFalse(NodeInferenceHub.hasLoadedModel.value)
    }

    @Test
    fun `clear removes bridge and marks model as unloaded`() {
        NodeInferenceHub.bridge = FakeBridge(NodeOfferedModel("model-id", "Model"))

        NodeInferenceHub.clear()

        assertSame(AbsentNodeInferenceBridge, NodeInferenceHub.bridge)
        assertFalse(NodeInferenceHub.hasLoadedModel.value)
    }

    @Test
    fun `state flow emits loaded then unloaded when bridge changes`() = runTest {
        NodeInferenceHub.bridge = FakeBridge(NodeOfferedModel("model-id", "Model"))
        assertTrue(NodeInferenceHub.hasLoadedModel.value)

        NodeInferenceHub.bridge = FakeBridge(null)
        assertFalse(NodeInferenceHub.hasLoadedModel.value)
    }

    private class FakeBridge(
        private val model: NodeOfferedModel?,
    ) : NodeInferenceBridge {
        override fun currentModel(): NodeOfferedModel? = model

        override suspend fun complete(messages: List<NodeChatMessage>): Result<String> =
            Result.success("unused")
    }
}
