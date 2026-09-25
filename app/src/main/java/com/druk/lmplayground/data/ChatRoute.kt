package com.druk.lmplayground.data

/**
 * Which engine backs a [ChatSessionEntity].
 * LOCAL = on-device GGUF; CLOUD = pasted OpenAI-compatible key.
 */
object ChatRoute {
    const val LOCAL = "LOCAL"
    const val CLOUD = "CLOUD"
}
