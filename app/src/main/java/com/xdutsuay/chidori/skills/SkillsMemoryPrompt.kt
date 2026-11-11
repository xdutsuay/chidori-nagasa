package com.xdutsuay.chidori.skills

import com.xdutsuay.chidori.memory.DurableMemoryStore
import com.xdutsuay.chidori.memory.MemoryFact
import java.io.File

/**
 * Builds the CP9 system-prompt appendix: skills catalog + confirmed memory facts.
 * Callers supply already-loaded lists so this stays free of Android Context.
 */
object SkillsMemoryPrompt {
    const val SKILLS_HEADER = "## Skills"
    const val MEMORY_HEADER = "## Durable memory"

    fun appendToSystemPrompt(
        base: String,
        skills: List<SkillEntry>,
        confirmedFacts: List<MemoryFact>,
    ): String {
        val parts = mutableListOf<String>()
        if (base.isNotBlank()) parts.add(base.trimEnd())
        val skillsBlock = SkillMarkdownParser.formatForSystemPrompt(skills)
        if (skillsBlock.isNotBlank()) {
            parts.add("$SKILLS_HEADER\n$skillsBlock")
        }
        val mem = formatConfirmedMemory(confirmedFacts)
        if (mem.isNotBlank()) {
            parts.add(mem)
        }
        return parts.joinToString("\n\n")
    }

    fun formatConfirmedMemory(facts: List<MemoryFact>): String {
        val confirmed = facts.filter { it.confirmed }
        if (confirmed.isEmpty()) return ""
        return buildString {
            appendLine(MEMORY_HEADER)
            appendLine("Confirmed facts (user-approved; do not invent others):")
            for (f in confirmed) {
                append("- ")
                appendLine(f.text.trim())
            }
        }.trimEnd()
    }

    fun loadSkillsDir(filesDir: File): List<SkillEntry> =
        SkillMarkdownParser.loadFromDirectory(File(filesDir, "skills"))

    fun confirmedFacts(store: DurableMemoryStore): List<MemoryFact> =
        store.listFacts().filter { it.confirmed }
}
