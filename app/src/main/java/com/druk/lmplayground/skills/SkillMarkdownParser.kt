package com.druk.lmplayground.skills

import java.io.File

/**
 * Minimal skills.md loader: title from first `# heading` or filename stem;
 * body is the remainder (trimmed). No YAML frontmatter required.
 */
object SkillMarkdownParser {

    fun parse(id: String, filenameStem: String, markdown: String): SkillEntry {
        val lines = markdown.replace("\r\n", "\n").lines()
        val headingIdx = lines.indexOfFirst { it.trimStart().startsWith("# ") }
        val title: String
        val body: String
        if (headingIdx >= 0) {
            title = lines[headingIdx].trimStart().removePrefix("# ").trim().ifBlank { filenameStem }
            body = lines.drop(headingIdx + 1).joinToString("\n").trim()
        } else {
            title = filenameStem
            body = markdown.trim()
        }
        return SkillEntry(id = id, title = title, body = body)
    }

    fun loadFromDirectory(dir: File): List<SkillEntry> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                val stem = file.nameWithoutExtension
                val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
                parse(id = stem, filenameStem = stem, markdown = text)
            }
            ?: emptyList()
    }

    fun formatForSystemPrompt(skills: List<SkillEntry>): String {
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("Available skills:")
            for (s in skills) {
                append("- ")
                append(s.title)
                if (s.body.isNotBlank()) {
                    append(": ")
                    append(s.body.lineSequence().first().trim())
                }
                appendLine()
            }
        }.trimEnd()
    }
}
