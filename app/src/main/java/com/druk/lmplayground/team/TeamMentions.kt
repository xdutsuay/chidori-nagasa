package com.druk.lmplayground.team

/**
 * Parses `@Planner` / `@Worker` / `@Critic` mentions (case-insensitive).
 * Order follows first appearance in [text]; duplicates are kept once.
 */
object TeamMentions {
    private val pattern = Regex("""(?:^|[\s(,])@([A-Za-z][A-Za-z0-9_]*)""")

    fun parseMentions(text: String): List<TeamRole> {
        if (text.isBlank()) return emptyList()
        val seen = linkedSetOf<TeamRole>()
        for (m in pattern.findAll(text)) {
            val role = TeamRole.fromMention(m.groupValues[1]) ?: continue
            seen.add(role)
        }
        // Also accept a leading @Role with no preceding whitespace (pattern uses ^).
        return seen.toList()
    }
}
