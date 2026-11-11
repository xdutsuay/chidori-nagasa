package com.xdutsuay.chidori.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LibraryQuery]: Unicode letter/digit term extraction,
 * lowercasing, dropping terms shorter than 2 chars, first-order de-duplication,
 * a 12-term cap, FTS5 `OR` query construction with quoted terms, and null when
 * there are no terms. FTS operators/quotes in the input must never leak.
 */
class LibraryQueryTest {

    // ---- terms ----

    @Test
    fun `terms are lowercased letter-digit runs in first order`() {
        assertEquals(listOf("how", "do", "reset", "the", "router"), LibraryQuery.terms("How do I reset the Router?"))
    }

    @Test
    fun `single-letter terms are dropped`() {
        assertEquals(emptyList<String>(), LibraryQuery.terms("a I x"))
        assertEquals(listOf("ab"), LibraryQuery.terms("a ab I"))
    }

    @Test
    fun `digits join letters into one term`() {
        assertEquals(listOf("abc123", "42", "v2"), LibraryQuery.terms("abc123 42 v2"))
        // Digit-only run of length 2 is kept; length 1 dropped.
        assertEquals(listOf("42"), LibraryQuery.terms("4 42"))
    }

    @Test
    fun `unicode letters are kept`() {
        val terms = LibraryQuery.terms("I café नमस्ते a")
        assertTrue("expected 'café' as a term, got: $terms", "café" in terms)
        assertTrue(
            "expected a Devanagari term of length >= 2, got: $terms",
            terms.any { term -> term.length >= 2 && term.any { it.code > 0x0900 && it.code < 0x0980 } },
        )
    }

    @Test
    fun `duplicates keep first order`() {
        assertEquals(listOf("dog", "cat", "bird"), LibraryQuery.terms("dog cat DOG bird Cat dog"))
    }

    @Test
    fun `at most 12 terms are kept`() {
        val query = (1..15).joinToString(" ") { "term$it" }
        val terms = LibraryQuery.terms(query)
        assertEquals(12, terms.size)
        assertEquals((1..12).map { "term$it" }, terms)
    }

    @Test
    fun `no terms yields empty list`() {
        assertEquals(emptyList<String>(), LibraryQuery.terms(""))
        assertEquals(emptyList<String>(), LibraryQuery.terms("   "))
        assertEquals(emptyList<String>(), LibraryQuery.terms("!!! ??? ..."))
        assertEquals(emptyList<String>(), LibraryQuery.terms("a \" *"))
    }

    // ---- toFtsMatch ----

    @Test
    fun `fts match wraps terms in quotes joined by OR`() {
        assertEquals(
            "\"how\" OR \"do\" OR \"reset\" OR \"the\" OR \"router\"",
            LibraryQuery.toFtsMatch("How do I reset the Router?"),
        )
    }

    @Test
    fun `fts operators and quotes in input never leak`() {
        assertEquals(
            "\"foo\" OR \"or\" OR \"near\"",
            LibraryQuery.toFtsMatch("foo\" OR NEAR(x) *"),
        )
    }

    @Test
    fun `no terms yields null fts match`() {
        assertNull(LibraryQuery.toFtsMatch(""))
        assertNull(LibraryQuery.toFtsMatch("   "))
        assertNull(LibraryQuery.toFtsMatch("!!!"))
        assertNull(LibraryQuery.toFtsMatch("a I *"))
    }

    @Test
    fun `fts match is consistent with terms`() {
        val query = "Zebra crossings AND paths; zebra!"
        val terms = LibraryQuery.terms(query)
        val expected = terms.joinToString(" OR ") { "\"$it\"" }
        assertEquals(expected, LibraryQuery.toFtsMatch(query))
        assertTrue(terms.contains("zebra"))
    }
}
