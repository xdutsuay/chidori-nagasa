package com.druk.lmplayground.cloud

sealed class CloudError {
    data class Unauthorized(val message: String = "Invalid API key") : CloudError()
    data class PaymentRequired(val message: String = "Payment required") : CloudError()
    data class RateLimited(val message: String = "Rate limited — try again shortly") : CloudError()
    data class Http(val code: Int, val message: String) : CloudError()
    data class Network(val message: String) : CloudError()
    data class Parse(val message: String) : CloudError()
    data class NoKey(val message: String = "No API key saved for this provider") : CloudError()

    fun userMessage(): String = when (this) {
        is Unauthorized -> message
        is PaymentRequired -> message
        is RateLimited -> message
        is Http -> if (message.isNotBlank()) message else "HTTP $code"
        is Network -> message
        is Parse -> message
        is NoKey -> message
    }
}

data class CloudChatMessage(
    val role: String,
    val content: String,
)

data class CloudModelInfo(
    val id: String,
    val name: String = id,
)

data class CloudUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** Provider-reported cost in USD when present (OpenRouter). */
    val costUsd: Double? = null,
)

sealed class CloudStreamEvent {
    data class Delta(val text: String) : CloudStreamEvent()
    data class Finished(val usage: CloudUsage?) : CloudStreamEvent()
}
