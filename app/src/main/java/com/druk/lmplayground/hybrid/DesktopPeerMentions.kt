package com.druk.lmplayground.hybrid

/** Parses `@Desktop` peer mentions (case-insensitive). */
object DesktopPeerMentions {
    private val pattern = Regex("""(?:^|[\s(,])@Desktop\b""", RegexOption.IGNORE_CASE)

    fun hasDesktopMention(text: String): Boolean = pattern.containsMatchIn(text)

    fun parseDesktopMentions(text: String): Boolean = hasDesktopMention(text)
}
