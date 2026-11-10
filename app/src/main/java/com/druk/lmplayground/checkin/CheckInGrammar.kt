package com.druk.lmplayground.checkin

/**
 * Structured-output contract for idle extraction.
 *
 * The on-device sampler accepts a GBNF grammar only for tool calls
 * (`LlamaGenerationSession` / the native tool sampler). There is no Kotlin
 * API to attach an arbitrary grammar to a one-shot completion, so [GBNF] is
 * included in the prompt and [OpenItemExtractor.parse] drops anything that
 * is not the JSON object this grammar describes.
 */
object CheckInGrammar {
    val GBNF: String = """
        root ::= "{" ws "\"items\"" ws ":" ws "[" ws items ws "]" ws "}"
        items ::= (item (ws "," ws item)*)?
        item ::= "{" ws "\"kind\"" ws ":" ws kind ws "," ws "\"summary\"" ws ":" ws string ws "," ws "\"dueAt\"" ws ":" ws due ws "}"
        kind ::= "\"DECISION\"" | "\"DEADLINE\"" | "\"HALF_DONE\"" | "\"WAITING_ON_USER\""
        due ::= "null" | number
        string ::= "\"" chars "\""
        number ::= "-"? [0-9]+
        chars ::= [^"\\]*
        ws ::= [ \t\n]*
    """.trimIndent()

    const val SYSTEM_HINT: String =
        "Reply with one JSON object and nothing else. No markdown. " +
            "kinds: DECISION, DEADLINE, HALF_DONE, WAITING_ON_USER. " +
            "dueAt is epoch milliseconds or null. Omit items you are not sure about."

    fun prompt(transcript: String): String = buildString {
        appendLine(SYSTEM_HINT)
        appendLine("GBNF:")
        appendLine(GBNF)
        appendLine("Chat transcript:")
        append(transcript.take(MAX_TRANSCRIPT_CHARS))
    }

    const val MAX_TRANSCRIPT_CHARS = 4000
}
