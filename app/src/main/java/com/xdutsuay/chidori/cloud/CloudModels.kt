package com.xdutsuay.chidori.cloud

sealed class CloudError {
    data class Unauthorized(val message: String = "Invalid API key") : CloudError()
    data class PaymentRequired(val message: String = "Payment required") : CloudError()
    data class RateLimited(val message: String = "Rate limited — try again shortly") : CloudError()
    data class Http(val code: Int, val message: String) : CloudError()
    data class Network(val message: String) : CloudError()
    data class Parse(val message: String) : CloudError()
    data class NoKey(val message: String = "No API key saved for this provider") : CloudError()
    data class EmptyReply(val message: String = "The model returned an empty reply") : CloudError()

    fun userMessage(): String = when (this) {
        is Unauthorized -> message
        is PaymentRequired -> message
        is RateLimited -> message
        is Http -> if (message.isNotBlank()) message else "HTTP $code"
        is Network -> message
        is Parse -> message
        is NoKey -> message
        is EmptyReply -> message
    }
}

data class CloudChatMessage(
    /** system | user | assistant | tool */
    val role: String,
    val content: String,
    /** Assistant tool calls, `[{"id","name","arguments"}]`. */
    val toolCallsJson: String? = null,
    /** Tool result: the call it answers. */
    val toolCallId: String? = null,
    /** User message carrying a screenshot (PNG, base64). */
    val imagePngBase64: String? = null,
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

    /** Tool calls requested in this response, `[{"id","name","arguments"}]`; emitted once, before [Finished]. */
    data class ToolCalls(val json: String) : CloudStreamEvent()
    data class Finished(val usage: CloudUsage?) : CloudStreamEvent()
}
