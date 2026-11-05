package com.druk.lmplayground.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP6 — provenance label + privacy contract. Privacy must never be ambiguous:
 * only [InferenceProvenance.Local] stays on device; every remote kind leaves.
 */
class InferenceProvenanceTest {

    @Test
    fun `only Local keeps data on device`() {
        assertFalse(InferenceProvenance.Local("qwen").leavesDevice)
        assertTrue(InferenceProvenance.CloudApi("OpenRouter").leavesDevice)
        assertTrue(InferenceProvenance.Desktop("studio").leavesDevice)
        assertTrue(InferenceProvenance.Node("pixel-9").leavesDevice)
    }

    @Test
    fun `formatLabel embeds kind prefix and model or peer name`() {
        assertEquals(
            "Local GGUF · qwen2.5-7b",
            InferenceProvenance.formatLabel(InferenceProvenance.Local("qwen2.5-7b")),
        )
        assertEquals(
            "Cloud API · OpenRouter",
            InferenceProvenance.formatLabel(InferenceProvenance.CloudApi("OpenRouter")),
        )
        assertEquals(
            "Desktop · studio-box",
            InferenceProvenance.formatLabel(InferenceProvenance.Desktop("studio-box")),
        )
        assertEquals(
            "Node · pixel-9",
            InferenceProvenance.formatLabel(InferenceProvenance.Node("pixel-9")),
        )
    }

    @Test
    fun `all kinds are distinct subtypes of the sealed hierarchy`() {
        val kinds: List<InferenceProvenance> = listOf(
            InferenceProvenance.Local("a"),
            InferenceProvenance.CloudApi("b"),
            InferenceProvenance.Desktop("c"),
            InferenceProvenance.Node("d"),
        )
        // Exhaustive `when` on the sealed class must cover all four leaves.
        kinds.forEach { p ->
            when (p) {
                is InferenceProvenance.Local,
                is InferenceProvenance.CloudApi,
                is InferenceProvenance.Desktop,
                is InferenceProvenance.Node,
                -> Unit
            }
        }
        assertEquals(4, kinds.map { it::class }.toSet().size)
    }

    @Test
    fun `kind name appears in label so the privacy tier is visible`() {
        val samples = listOf(
            InferenceProvenance.Local("m") to "Local GGUF",
            InferenceProvenance.CloudApi("p") to "Cloud API",
            InferenceProvenance.Desktop("d") to "Desktop",
            InferenceProvenance.Node("n") to "Node",
        )
        samples.forEach { (p, expectedKind) ->
            val label = InferenceProvenance.formatLabel(p)
            assertTrue("label '$label' missing kind '$expectedKind'", label.startsWith(expectedKind))
            assertTrue(label.contains("·"))
        }
    }

    @Test
    fun `equal provenance values are data-class equal`() {
        assertEquals(InferenceProvenance.Local("x"), InferenceProvenance.Local("x"))
        assertEquals(InferenceProvenance.Node("x"), InferenceProvenance.Node("x"))
    }
}
