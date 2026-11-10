package com.druk.lmplayground.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenItemExtractorTest {
    @Test
    fun parsesTheGrammarObject() {
        val raw = """
            {"items":[
              {"kind":"DECISION","summary":"Pick a storage folder","dueAt":null},
              {"kind":"DEADLINE","summary":"Reply to Sam","dueAt":1700000000000}
            ]}
        """.trimIndent()
        val items = OpenItemExtractor.parse(raw)!!
        assertEquals(2, items.size)
        assertEquals(OpenItemKind.DECISION, items[0].kind)
        assertNull(items[0].dueAtMs)
        assertEquals(1_700_000_000_000L, items[1].dueAtMs)
    }

    @Test
    fun stripsAMarkdownFence() {
        val raw = """
            ```json
            {"items":[{"kind":"HALF_DONE","summary":"Finish the note","dueAt":null}]}
            ```
        """.trimIndent()
        val items = OpenItemExtractor.parse(raw)!!
        assertEquals("Finish the note", items.single().summary)
    }

    @Test
    fun emptyItemsIsValidAndEmpty() {
        assertTrue(OpenItemExtractor.parse("""{"items":[]}""")!!.isEmpty())
    }

    @Test
    fun malformedPayloadIsDropped() {
        assertNull(OpenItemExtractor.parse("sure, I'll remind you"))
        assertNull(OpenItemExtractor.parse("""[{"kind":"DECISION","summary":"x","dueAt":null}]"""))
        assertNull(OpenItemExtractor.parse("""{"items":[{"kind":"MAYBE","summary":"x","dueAt":null}]}"""))
        assertNull(OpenItemExtractor.parse("""{"items":[{"kind":"DECISION","summary":"","dueAt":null}]}"""))
        assertNull(OpenItemExtractor.parse(""))
        assertNull(OpenItemExtractor.parse(null))
    }

    @Test
    fun chatCommands() {
        assertEquals(ChatCommand.DONE, parseChatCommand("Done."))
        assertEquals(ChatCommand.PARK, parseChatCommand("park it"))
        assertEquals(ChatCommand.CLOSE, parseChatCommand("Drop this"))
        assertEquals(ChatCommand.REMIND, parseChatCommand("remind me about the invoice"))
        assertNull(parseChatCommand("I am done with the long explanation"))
    }

    @Test
    fun fingerprintIsStableAndSummaryNormalized() {
        val a = openItemFingerprint("chat", "s1", "DECISION", "Pick  a folder")
        val b = openItemFingerprint("chat", "s1", "DECISION", "  pick a folder ")
        val c = openItemFingerprint("chat", "s1", "DEADLINE", "pick a folder")
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
