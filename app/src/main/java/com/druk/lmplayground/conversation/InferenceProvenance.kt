package com.druk.lmplayground.conversation

/**
 * CP6 — always-on route label for a chat turn / session.
 * Privacy must never be ambiguous: Local stays on device; others leave it.
 */
sealed class InferenceProvenance {
    /** On-device GGUF (or other local engine). */
    data class Local(val modelLabel: String) : InferenceProvenance()

    /** Cloud OpenAI-compatible provider configured in Settings → Cloud APIs. */
    data class CloudApi(val providerName: String) : InferenceProvenance()

    /** Remote chat / Ask through a paired Chidori desktop. */
    data class Desktop(val displayName: String) : InferenceProvenance()

    /** Inference via a phone offered as a LAN node (paired desktop consuming it). */
    data class Node(val displayName: String) : InferenceProvenance()

    val leavesDevice: Boolean
        get() = this !is Local

    companion object {
        /**
         * Short banner copy. Prefer resource strings at the UI boundary for
         * localization; this formatter is for pure unit tests and logs.
         */
        fun formatLabel(p: InferenceProvenance): String = when (p) {
            is Local -> "Local GGUF · ${p.modelLabel}"
            is CloudApi -> "Cloud API · ${p.providerName}"
            is Desktop -> "Desktop · ${p.displayName}"
            is Node -> "Node · ${p.displayName}"
        }
    }
}
