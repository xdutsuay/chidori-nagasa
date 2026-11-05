package com.druk.lmplayground.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP10 tests for `@Desktop` peer mention parsing (TeamMentions pattern reused).
 *
 * Production symbols exercised (package com.druk.lmplayground.hybrid):
 *  - object DesktopPeerMentions.hasDesktopMention(text: String): Boolean
 *  - object DesktopPeerMentions.parseDesktopMentions(text: String): Boolean
 */
class DesktopPeerMentionsTest {

    // ---- positive matches ----

    @Test
    fun `hasDesktopMention matches mention at start of text`() {
        assertTrue(DesktopPeerMentions.hasDesktopMention("@Desktop run this build"))
    }

    @Test
    fun `hasDesktopMention matches mid-sentence mention`() {
        assertTrue(DesktopPeerMentions.hasDesktopMention("hey @Desktop can you compile this?"))
    }

    @Test
    fun `hasDesktopMention is case insensitive`() {
        assertTrue(DesktopPeerMentions.hasDesktopMention("ping @desktop please"))
        assertTrue(DesktopPeerMentions.hasDesktopMention("ping @DESKTOP please"))
        assertTrue(DesktopPeerMentions.hasDesktopMention("ping @DeSkToP please"))
    }

    @Test
    fun `hasDesktopMention matches mention wrapped in parens`() {
        assertTrue(DesktopPeerMentions.hasDesktopMention("route to (@Desktop) next"))
    }

    @Test
    fun `hasDesktopMention matches mention after newline`() {
        assertTrue(DesktopPeerMentions.hasDesktopMention("first line\n@Desktop second line"))
    }

    @Test
    fun `hasDesktopMention matches mention followed by punctuation`() {
        assertTrue(DesktopPeerMentions.hasDesktopMention("@Desktop, take this turn"))
        assertTrue(DesktopPeerMentions.hasDesktopMention("over to @Desktop."))
    }

    @Test
    fun `parseDesktopMentions agrees with hasDesktopMention`() {
        val samples = listOf(
            "@Desktop hi",
            "plain text",
            "",
            "cc @desktop",
        )
        for (text in samples) {
            assertEquals(
                DesktopPeerMentions.hasDesktopMention(text),
                DesktopPeerMentions.parseDesktopMentions(text),
            )
        }
    }

    // ---- negative matches ----

    @Test
    fun `hasDesktopMention empty and blank text returns false`() {
        assertFalse(DesktopPeerMentions.hasDesktopMention(""))
        assertFalse(DesktopPeerMentions.hasDesktopMention("   \n\t "))
    }

    @Test
    fun `hasDesktopMention plain text without mention returns false`() {
        assertFalse(DesktopPeerMentions.hasDesktopMention("no mentions here at all"))
    }

    @Test
    fun `hasDesktopMention ignores other role mentions`() {
        assertFalse(DesktopPeerMentions.hasDesktopMention("@Planner plan it and @Worker run it"))
    }

    @Test
    fun `hasDesktopMention ignores desktop embedded in a longer token`() {
        assertFalse(DesktopPeerMentions.hasDesktopMention("the @Desktops are busy"))
        assertFalse(DesktopPeerMentions.hasDesktopMention("email me at me@Desktop.example"))
    }

    @Test
    fun `hasDesktopMention ignores desktop without at sign`() {
        assertFalse(DesktopPeerMentions.hasDesktopMention("ask Desktop directly"))
    }

    @Test
    fun `hasDesktopMention requires preceding boundary so it does not fire mid-word`() {
        assertFalse(DesktopPeerMentions.hasDesktopMention("abc@Desktop def"))
    }
}
