package com.druk.lmplayground.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CP9 tests for the user-authored skills catalog (the device `skills` markdown folder).
 *
 * Production symbols exercised (package com.druk.lmplayground.skills):
 *  - data class SkillEntry(id: String, title: String, body: String)  [exists]
 *  - object SkillMarkdownParser:
 *      parse(id: String, filenameStem: String, markdown: String): SkillEntry
 *      - title = text of the first h1 heading (a line starting with `# `),
 *        trimmed; falls back to [filenameStem] when the heading text is blank
 *      - body = everything after the h1 heading line, joined and trimmed;
 *        when there is no h1, body = the whole markdown trimmed
 *      - tolerates CRLF line endings (Windows-authored files)
 *      loadFromDirectory(dir: File): List<SkillEntry>
 *      - reads every `*.md` file in dir (non-recursive), sorted by file name
 *      - id = filename stem; non-`.md` files are ignored
 *      - missing directory or unreadable file entries are skipped / empty
 *      formatForSystemPrompt(skills: List<SkillEntry>): String
 *      - "" for an empty list
 *      - otherwise exactly:
 *          "Available skills:"
 *          "- <title>: <first line of body>"   (colon+body line omitted when body blank)
 *        one line per skill, in list order (compact catalog)
 */
class SkillCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── SkillMarkdownParser.parse ──────────────────────────────────────────

    @Test
    fun `parser takes title from first h1 heading and body from the rest`() {
        val entry = SkillMarkdownParser.parse(
            id = "deploy-check",
            filenameStem = "deploy-check",
            markdown = """
                # Deploy check

                Run assembleDebug.

                Then smoke test the APK.
            """.trimIndent(),
        )
        assertEquals(
            SkillEntry("deploy-check", "Deploy check", "Run assembleDebug.\n\nThen smoke test the APK."),
            entry,
        )
    }

    @Test
    fun `parser falls back to filename stem when there is no h1`() {
        val entry = SkillMarkdownParser.parse(
            id = "flash-mode",
            filenameStem = "flash-mode",
            markdown = "Just switch the model to flash mode.",
        )
        assertEquals(SkillEntry("flash-mode", "flash-mode", "Just switch the model to flash mode."), entry)
    }

    @Test
    fun `parser uses h1 over filename stem when both exist`() {
        val entry = SkillMarkdownParser.parse(
            id = "release",
            filenameStem = "release",
            markdown = "# Ship it\n\nsteps",
        )
        assertEquals("Ship it", entry.title)
        assertEquals("steps", entry.body)
    }

    @Test
    fun `parser ignores lower-level headings for title`() {
        // `## Sub` is not an h1, so the filename stem is the title.
        val entry = SkillMarkdownParser.parse(
            id = "notes",
            filenameStem = "notes",
            markdown = "## Sub\n\ndetail text",
        )
        assertEquals("notes", entry.title)
        // No h1 to split on, so the whole content is the body.
        assertEquals("## Sub\n\ndetail text", entry.body)
    }

    @Test
    fun `parser falls back to filename stem when h1 text is blank`() {
        val entry = SkillMarkdownParser.parse(
            id = "blank-title",
            filenameStem = "blank-title",
            markdown = "#   \n\nbody here",
        )
        assertEquals("blank-title", entry.title)
        assertEquals("body here", entry.body)
    }

    @Test
    fun `parser tolerates CRLF line endings`() {
        val entry = SkillMarkdownParser.parse(
            id = "crlf",
            filenameStem = "crlf",
            markdown = "# Windows skill\r\n\r\nDo the thing.\r\n",
        )
        assertEquals("Windows skill", entry.title)
        assertEquals("Do the thing.", entry.body)
    }

    @Test
    fun `parser keeps body intact including markdown`() {
        val body = "1. first\n2. second\n\n```sh\n./gradlew test\n```"
        val entry = SkillMarkdownParser.parse(id = "steps", filenameStem = "steps", markdown = "# Steps\n\n$body")
        assertEquals(body, entry.body)
    }

    // ── SkillMarkdownParser.loadFromDirectory ──────────────────────────────

    @Test
    fun `loadFromDirectory loads every md file sorted by name`() {
        val dir = tmp.newFolder("skills")
        File(dir, "zzz-last.md").writeText("# Last skill\n\ndoes z work")
        File(dir, "aaa-first.md").writeText("# First skill\n\ndoes a work")
        File(dir, "mmm-middle.md").writeText("# Middle skill\n\ndoes m work")

        val skills = SkillMarkdownParser.loadFromDirectory(dir)
        assertEquals(listOf("aaa-first", "mmm-middle", "zzz-last"), skills.map { it.id })
        assertEquals(listOf("First skill", "Middle skill", "Last skill"), skills.map { it.title })
    }

    @Test
    fun `loadFromDirectory ignores non-md files`() {
        val dir = tmp.newFolder("skills")
        File(dir, "real.md").writeText("# Real\n\ncontent")
        File(dir, "notes.txt").writeText("# Not a skill\n\nignore me")
        File(dir, "image.png").writeBytes(byteArrayOf(1, 2, 3))

        val skills = SkillMarkdownParser.loadFromDirectory(dir)
        assertEquals(listOf("real"), skills.map { it.id })
    }

    @Test
    fun `loadFromDirectory returns empty list for a missing directory`() {
        val missing = File(tmp.root, "does-not-exist")
        assertTrue(SkillMarkdownParser.loadFromDirectory(missing).isEmpty())
    }

    @Test
    fun `loadFromDirectory returns empty list for an empty directory`() {
        assertTrue(SkillMarkdownParser.loadFromDirectory(tmp.newFolder("empty")).isEmpty())
    }

    @Test
    fun `loadFromDirectory parses entries with stem fallback titles`() {
        val dir = tmp.newFolder("skills")
        File(dir, "no-heading.md").writeText("plain body only")

        val skills = SkillMarkdownParser.loadFromDirectory(dir)
        assertEquals(1, skills.size)
        assertEquals(SkillEntry("no-heading", "no-heading", "plain body only"), skills.single())
    }

    // ── SkillMarkdownParser.formatForSystemPrompt ──────────────────────────

    @Test
    fun `formatForSystemPrompt is empty for no skills`() {
        assertEquals("", SkillMarkdownParser.formatForSystemPrompt(emptyList()))
    }

    @Test
    fun `formatForSystemPrompt renders one compact line per skill`() {
        val catalog = SkillMarkdownParser.formatForSystemPrompt(
            listOf(
                SkillEntry("deploy-check", "Deploy check", "Run assembleDebug."),
                SkillEntry("flash-mode", "flash-mode", "Switch to flash."),
            ),
        )
        assertEquals(
            "Available skills:\n- Deploy check: Run assembleDebug.\n- flash-mode: Switch to flash.",
            catalog,
        )
    }

    @Test
    fun `formatForSystemPrompt omits the colon when the body is blank`() {
        val catalog = SkillMarkdownParser.formatForSystemPrompt(
            listOf(SkillEntry("empty", "Empty skill", "")),
        )
        assertEquals("Available skills:\n- Empty skill", catalog)
    }

    @Test
    fun `formatForSystemPrompt keeps skills in list order`() {
        val catalog = SkillMarkdownParser.formatForSystemPrompt(
            listOf(
                SkillEntry("b", "B", "body b"),
                SkillEntry("a", "A", "body a"),
            ),
        )
        assertTrue(catalog.indexOf("- B") < catalog.indexOf("- A"))
    }

    @Test
    fun `formatForSystemPrompt is compact only first body line is included`() {
        val catalog = SkillMarkdownParser.formatForSystemPrompt(
            listOf(SkillEntry("one", "One", "line1\n\nline2")),
        )
        assertEquals("Available skills:\n- One: line1", catalog)
    }
}
