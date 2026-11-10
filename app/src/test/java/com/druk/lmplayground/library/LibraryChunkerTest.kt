package com.druk.lmplayground.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LibraryChunker] (see the library contract handout):
 *
 * - blank input -> empty list; short input -> one trimmed chunk
 * - every chunk is non-blank, trimmed, and <= chunkChars
 * - break preference: paragraph ("\n\n") > newline > space, searched backwards
 *   from the limit but never before half the chunk; hard cut otherwise
 * - no content loss: the chunks' concatenation covers every non-whitespace
 *   character of the input, in order
 * - require() validation throws IllegalArgumentException
 */
class LibraryChunkerTest {

    private fun assertValidChunks(chunks: List<String>, chunkChars: Int) {
        assertTrue("expected at least one chunk", chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertTrue("chunk must not be blank", chunk.isNotBlank())
            assertEquals("chunk must be trimmed", chunk, chunk.trim())
            assertTrue("chunk length ${chunk.length} exceeds chunkChars=$chunkChars", chunk.length <= chunkChars)
        }
    }

    /**
     * Contract: "concatenating chunks covers every non-whitespace character of
     * the input in order". The concatenated chunks are a sequence of slices of
     * the input, so the non-whitespace characters of the input must appear in
     * the concatenation as an in-order subsequence (overlap only duplicates).
     */
    private fun assertNonWhitespaceCoveredInOrder(input: String, chunks: List<String>) {
        val concatenated = chunks.joinToString("")
        var cursor = 0
        input.forEachIndexed { inputIndex, c ->
            if (c.isWhitespace()) return@forEachIndexed
            var found = -1
            var i = cursor
            while (i < concatenated.length) {
                if (concatenated[i] == c) {
                    found = i
                    break
                }
                i++
            }
            assertTrue(
                "input char '$c' at index $inputIndex is not covered by the chunks in order",
                found >= 0,
            )
            cursor = found + 1
        }
    }

    // ---- constants / defaults ----

    @Test
    fun `contract constants are 1000 and 150`() {
        assertEquals(1000, LibraryChunker.CHUNK_CHARS)
        assertEquals(150, LibraryChunker.OVERLAP_CHARS)
    }

    // ---- blank / short input ----

    @Test
    fun `blank and whitespace-only text produce empty list`() {
        assertEquals(emptyList<String>(), LibraryChunker.chunk(""))
        assertEquals(emptyList<String>(), LibraryChunker.chunk("   "))
        assertEquals(emptyList<String>(), LibraryChunker.chunk(" \n\t \r\n "))
    }

    @Test
    fun `text at or below chunkChars is a single trimmed chunk`() {
        assertEquals(listOf("hello world"), LibraryChunker.chunk("hello world", 100, 10))
        assertEquals(listOf("  padded  ".trim()), LibraryChunker.chunk("  padded  ", 100, 10))
        val exact = "a".repeat(1000)
        assertEquals(listOf(exact), LibraryChunker.chunk(exact))
        // Exactly chunkChars after trim still fits in one chunk.
        assertEquals(listOf("x".repeat(18)), LibraryChunker.chunk("x".repeat(18) + "  ", 20, 5))
    }

    // ---- general invariants on long documents ----

    @Test
    fun `long multi-paragraph document satisfies chunk invariants and coverage`() {
        val paragraph = "The library indexer splits documents into overlapping chunks. "
        val text = (1..40).joinToString("\n\n") { "Paragraph $it: " + paragraph.repeat(4) }
        val chunks = LibraryChunker.chunk(text)
        assertValidChunks(chunks, LibraryChunker.CHUNK_CHARS)
        assertNonWhitespaceCoveredInOrder(text, chunks)
        assertTrue("document should produce several chunks", chunks.size > 1)
    }

    @Test
    fun `trailing whitespace never survives into chunks`() {
        val text = ("word " + "y".repeat(30) + "   ").repeat(10)
        val chunks = LibraryChunker.chunk(text, 50, 10)
        assertValidChunks(chunks, 50)
    }

    // ---- break-point preference ----

    @Test
    fun `breaks at paragraph boundary when one is inside the search window`() {
        // 600 A's, "\n\n" at 600..601, then 600 B's, "\n\n", 600 C's.
        val text = "A".repeat(600) + "\n\n" + "B".repeat(600) + "\n\n" + "C".repeat(600)
        val chunks = LibraryChunker.chunk(text, 1000, 150)
        assertValidChunks(chunks, 1000)
        assertNonWhitespaceCoveredInOrder(text, chunks)
        // The first chunk must end at the paragraph boundary, not mid-B.
        assertEquals("A".repeat(600), chunks[0])
    }

    @Test
    fun `paragraph boundary wins over a closer space`() {
        // A*90, "\n\n" at 90..91, "Bbb" 92..94, space at 95, then C's.
        val text = "A".repeat(90) + "\n\n" + "B".repeat(3) + " " + "C".repeat(40)
        val chunks = LibraryChunker.chunk(text, 100, 10)
        // The "\n\n" (index 90) is inside the [50, 100) window and must be
        // preferred over the space at 95.
        assertEquals("A".repeat(90), chunks[0])
        assertNonWhitespaceCoveredInOrder(text, chunks)
    }

    @Test
    fun `falls back to newline when no paragraph boundary is in the window`() {
        // A*90, single "\n" at 90, "Bbb" 91..93, space at 94, then C's.
        val text = "A".repeat(90) + "\n" + "B".repeat(3) + " " + "C".repeat(40)
        val chunks = LibraryChunker.chunk(text, 100, 10)
        assertEquals("A".repeat(90), chunks[0])
        assertNonWhitespaceCoveredInOrder(text, chunks)
    }

    @Test
    fun `paragraph boundary before half the chunk is ignored in favor of a later space`() {
        // A*30, "\n\n" at 30..31 (before half=50), B*47 -> 32..78, space at 79, C*50.
        val text = "A".repeat(30) + "\n\n" + "B".repeat(47) + " " + "C".repeat(50)
        val chunks = LibraryChunker.chunk(text, 100, 10)
        // The "\n\n" is outside the [50, 100) window, so the space at 79 breaks.
        // Chunks are trimmed substrings of the original, so inner "\n\n" stays.
        assertEquals("A".repeat(30) + "\n\n" + "B".repeat(47), chunks[0])
        assertNonWhitespaceCoveredInOrder(text, chunks)
    }

    @Test
    fun `falls back to space when no newline is in the window`() {
        // Words of 5-6 chars + single space; with chunkChars=20 the last space
        // before the limit is at index 17.
        val text = "alpha beta gamma delta epsilon"
        val chunks = LibraryChunker.chunk(text, 20, 5)
        assertValidChunks(chunks, 20)
        assertNonWhitespaceCoveredInOrder(text, chunks)
        assertEquals("alpha beta gamma", chunks[0])
    }

    @Test
    fun `break candidates before half the chunk are ignored leading to a hard cut`() {
        // The only space is at index 1, far before half of chunkChars=100, so
        // the chunker must hard-cut at 100 instead of breaking at that space.
        val text = "x" + " " + "y".repeat(3000)
        val chunks = LibraryChunker.chunk(text, 100, 20)
        assertValidChunks(chunks, 100)
        assertEquals("first chunk must be a hard cut of exactly chunkChars", 100, chunks[0].length)
        assertNonWhitespaceCoveredInOrder(text, chunks)
    }

    // ---- hard cut ----

    @Test
    fun `single long word hard-cuts into exact-size chunks except the last`() {
        val word = buildString {
            for (i in 0 until 5000) append('a' + (i % 26))
        }
        val chunks = LibraryChunker.chunk(word)
        assertValidChunks(chunks, LibraryChunker.CHUNK_CHARS)
        assertTrue("hard cut should produce several chunks", chunks.size > 1)
        for (i in 0 until chunks.size - 1) {
            assertEquals("non-final hard-cut chunk must be exactly chunkChars", LibraryChunker.CHUNK_CHARS, chunks[i].length)
        }
        assertNonWhitespaceCoveredInOrder(word, chunks)
    }

    // ---- no content loss (overlap) ----

    @Test
    fun `consecutive chunks never lose content across many sizes`() {
        val text = (1..60).joinToString(" ") { "token$it" }
        listOf(
            40 to 5,
            37 to 12,
            25 to 1,
            25 to 24,
        ).forEach { (chunkChars, overlap) ->
            val chunks = LibraryChunker.chunk(text, chunkChars, overlap)
            assertValidChunks(chunks, chunkChars)
            assertNonWhitespaceCoveredInOrder(text, chunks)
        }
    }

    // ---- require() validation ----

    @Test
    fun `invalid parameters throw IllegalArgumentException`() {
        val text = "some text to chunk"
        assertThrowsIae { LibraryChunker.chunk(text, 0, 5) }
        assertThrowsIae { LibraryChunker.chunk(text, -1, 5) }
        assertThrowsIae { LibraryChunker.chunk(text, 10, -1) }
        assertThrowsIae { LibraryChunker.chunk(text, 10, 10) } // overlap must be < chunkChars
        assertThrowsIae { LibraryChunker.chunk(text, 10, 25) }
        // Overlap of 0 is explicitly allowed by the contract (0 until chunkChars).
        val chunks = LibraryChunker.chunk("z".repeat(30), 10, 0)
        assertValidChunks(chunks, 10)
        assertNonWhitespaceCoveredInOrder("z".repeat(30), chunks)
    }

    private fun assertThrowsIae(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
