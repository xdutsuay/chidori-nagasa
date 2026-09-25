package com.druk.lmplayground.cloud

/**
 * OpenAI-compatible URL helpers for the cloud chat package.
 * This package must not import [com.druk.lmplayground.inference] or
 * [com.druk.lmplayground.coordinator].
 */
object CloudEndpoints {
    /**
     * Join a provider base URL (often ending in `/v1`) with a relative path
     * such as `models` or `chat/completions` without producing a double slash.
     */
    fun join(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val rel = path.trim().trimStart('/')
        require(base.isNotEmpty()) { "base URL is empty" }
        require(rel.isNotEmpty()) { "path is empty" }
        return "$base/$rel"
    }

    fun modelsUrl(baseUrl: String): String = join(baseUrl, "models")

    fun chatCompletionsUrl(baseUrl: String): String = join(baseUrl, "chat/completions")
}
