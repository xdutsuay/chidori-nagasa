package com.druk.lmplayground.cloud

import java.net.URI

data class CloudPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
)

object CloudPresets {
    val OPENROUTER = CloudPreset(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
    )
    val OPENAI = CloudPreset(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
    )
    val GROQ = CloudPreset(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
    )

    val ALL: List<CloudPreset> = listOf(OPENROUTER, OPENAI, GROQ)

    /**
     * Validates a provider base URL.
     * @return null when valid, otherwise a short reason string for the UI.
     */
    fun validateBaseUrl(raw: String, allowHttp: Boolean): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Base URL is empty"
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return "Base URL is not a valid URI"
        }
        val scheme = uri.scheme?.lowercase()
        when {
            scheme == "https" -> Unit
            scheme == "http" && allowHttp -> Unit
            scheme == "http" -> return "HTTP is blocked unless Allow HTTP is on"
            else -> return "Base URL must start with https://"
        }
        if (uri.host.isNullOrBlank()) return "Base URL needs a host"
        return null
    }
}
