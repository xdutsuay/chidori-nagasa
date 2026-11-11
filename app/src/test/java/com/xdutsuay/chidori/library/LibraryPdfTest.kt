package com.xdutsuay.chidori.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Library v2 PDF surface:
 *
 * - [LibraryFormats.isPdf]: extension-based, case-insensitive, and false for
 *   the `extensionOf` null cases (no dot, leading dot only, trailing dot)
 * - [LibraryFormats.isSupported]: text formats always; PDF gated on pdfSupported
 * - [LibraryFormats.maxBytes]: PDFs get [LibraryFormats.MAX_PDF_BYTES],
 *   everything else [LibraryFormats.MAX_TEXT_BYTES] (exactly 2 MiB)
 * - [LibraryPdfText.join]: keeps original 1-based page numbers, drops blank
 *   pages, converts CRLF to LF, and uses only the first
 *   [LibraryPdfText.MAX_PAGES] pages
 */
class LibraryPdfTest {

    // ---- isPdf ----

    @Test
    fun `pdf extension matches case-insensitively`() {
        assertTrue(LibraryFormats.isPdf("a.pdf"))
        assertTrue(LibraryFormats.isPdf("A.PDF"))
        assertTrue(LibraryFormats.isPdf("x.y.Pdf"))
    }

    @Test
    fun `non-pdf names are not pdf`() {
        // No dot at all -> extensionOf is null.
        assertFalse(LibraryFormats.isPdf("pdf"))
        // Leading dot only -> extensionOf is null (dot <= 0).
        assertFalse(LibraryFormats.isPdf(".pdf"))
        // Wrong extension.
        assertFalse(LibraryFormats.isPdf("a.pdfx"))
        assertFalse(LibraryFormats.isPdf("a.txt"))
        // Extension is "txt", not "pdf".
        assertFalse(LibraryFormats.isPdf("a.pdf.txt"))
    }

    // ---- isSupported ----

    @Test
    fun `pdf is unsupported unless pdfSupported is true`() {
        assertFalse(LibraryFormats.isSupported("a.pdf"))
        assertFalse(LibraryFormats.isSupported("a.pdf", pdfSupported = false))
        assertTrue(LibraryFormats.isSupported("a.pdf", pdfSupported = true))
    }

    @Test
    fun `text formats are supported regardless of pdfSupported`() {
        listOf(false, true).forEach { pdfSupported ->
            assertTrue(LibraryFormats.isSupported("a.md", pdfSupported))
            assertTrue(LibraryFormats.isSupported("b.TXT", pdfSupported))
        }
    }

    @Test
    fun `unknown extensions are unsupported regardless of pdfSupported`() {
        listOf(false, true).forEach { pdfSupported ->
            assertFalse(LibraryFormats.isSupported("a.docx", pdfSupported))
            assertFalse(LibraryFormats.isSupported("noext", pdfSupported))
        }
    }

    // ---- maxBytes ----

    @Test
    fun `text budget is exactly 2 MiB`() {
        assertEquals(2L * 1024 * 1024, LibraryFormats.MAX_TEXT_BYTES)
    }

    @Test
    fun `pdf budget is larger than the text budget`() {
        assertTrue(LibraryFormats.MAX_PDF_BYTES > LibraryFormats.MAX_TEXT_BYTES)
    }

    @Test
    fun `maxBytes follows the file kind`() {
        assertEquals(LibraryFormats.MAX_PDF_BYTES, LibraryFormats.maxBytes("a.pdf"))
        assertEquals(LibraryFormats.MAX_PDF_BYTES, LibraryFormats.maxBytes("A.PDF"))
        // Text formats use the text budget...
        assertEquals(LibraryFormats.MAX_TEXT_BYTES, LibraryFormats.maxBytes("a.md"))
        // ...and so does everything unknown (no extension, unsupported extension).
        assertEquals(LibraryFormats.MAX_TEXT_BYTES, LibraryFormats.maxBytes("a.docx"))
        assertEquals(LibraryFormats.MAX_TEXT_BYTES, LibraryFormats.maxBytes("noext"))
    }

    // ---- LibraryPdfText.join ----

    @Test
    fun `join keeps original page numbers, drops blank pages, converts CRLF`() {
        val joined = LibraryPdfText.join(listOf("Hello", "", "  World \r\nline"))
        assertEquals("Page 1\nHello\n\nPage 3\nWorld\nline", joined)
    }

    @Test
    fun `join of empty or all-blank pages is the empty string`() {
        assertEquals("", LibraryPdfText.join(emptyList()))
        assertEquals("", LibraryPdfText.join(listOf("", "   ", " \r\n\t")))
    }

    @Test
    fun `join uses only the first MAX_PAGES pages with original numbering`() {
        val pages = (1..(LibraryPdfText.MAX_PAGES + 5)).map { "p$it" }
        val joined = LibraryPdfText.join(pages)
        val last = LibraryPdfText.MAX_PAGES
        // Trailing "\n" keeps "Page 50" from matching as a prefix of "Page 500".
        assertTrue("expected 'Page $last\\n' in output", joined.contains("Page $last\n"))
        assertFalse(
            "page ${last + 1} must be dropped",
            joined.contains("Page ${last + 1}\n"),
        )
    }
}
