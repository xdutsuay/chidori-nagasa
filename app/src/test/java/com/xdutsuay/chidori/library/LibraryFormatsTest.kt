package com.xdutsuay.chidori.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [LibraryFormats]: supported-extension set, case-insensitive
 * matching, and HTML-to-plain-text conversion (tags and script/style content
 * stripped), with CRLF normalization for everything else.
 */
class LibraryFormatsTest {

    // ---- TEXT_EXTENSIONS ----

    @Test
    fun `TEXT_EXTENSIONS contains the contracted extensions`() {
        val expected = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "html", "htm", "log",
            "xml", "yaml", "yml", "kt", "java", "py", "js", "ts", "go", "rs",
            "c", "cpp", "h", "sh",
        )
        assertTrue(
            "missing extensions: " + (expected - LibraryFormats.TEXT_EXTENSIONS),
            LibraryFormats.TEXT_EXTENSIONS.containsAll(expected),
        )
    }

    // ---- isSupported ----

    @Test
    fun `supported extensions match case-insensitively`() {
        assertTrue(LibraryFormats.isSupported("README.MD"))
        assertTrue(LibraryFormats.isSupported("notes.TXT"))
        assertTrue(LibraryFormats.isSupported("data.JSON"))
        assertTrue(LibraryFormats.isSupported("index.HTML"))
        assertTrue(LibraryFormats.isSupported("Main.Kt"))
    }

    @Test
    fun `unsupported files are rejected`() {
        assertFalse(LibraryFormats.isSupported("photo.jpg"))
        assertFalse(LibraryFormats.isSupported("doc.pdf"))
        assertFalse(LibraryFormats.isSupported("archive.tar.gz"))
        assertFalse(LibraryFormats.isSupported("image.PNG"))
        assertFalse(LibraryFormats.isSupported("song.mp3"))
        assertFalse(LibraryFormats.isSupported("noextension"))
        assertFalse(LibraryFormats.isSupported(""))
    }

    @Test
    fun `supported lowercase names pass`() {
        assertTrue(LibraryFormats.isSupported("notes.txt"))
        assertTrue(LibraryFormats.isSupported("App.kt"))
        assertTrue(LibraryFormats.isSupported("Main.java"))
        assertTrue(LibraryFormats.isSupported("script.sh"))
    }

    // ---- toPlainText ----

    @Test
    fun `html strips tags and script and style contents`() {
        val html =
            "<html><head><style>p{}</style></head><body><p>Hello <b>world</b></p><script>x()</script></body></html>"
        val text = LibraryFormats.toPlainText("page.html", html)
        assertTrue("expected visible text, got: $text", text.contains("Hello"))
        assertTrue("expected visible text, got: $text", text.contains("world"))
        assertFalse("script content must be removed", text.contains("x()"))
        assertFalse("style content must be removed", text.contains("p{}"))
    }

    @Test
    fun `htm extension is treated as html`() {
        val text = LibraryFormats.toPlainText("page.htm", "<div>Body text</div>")
        assertTrue(text.contains("Body text"))
        assertFalse(text.contains("<div>"))
    }

    @Test
    fun `html matching is case-insensitive`() {
        val text = LibraryFormats.toPlainText("README.HTML", "<p>Upper case tags</p>")
        assertTrue(text.contains("Upper case tags"))
        assertFalse(text.contains("<p>"))
    }

    @Test
    fun `non-html formats are returned unchanged except CRLF normalization`() {
        assertEquals("line1\nline2", LibraryFormats.toPlainText("notes.txt", "line1\r\nline2"))
        assertEquals("a\nb\rc\nd", LibraryFormats.toPlainText("notes.md", "a\r\nb\rc\r\nd"))
        assertEquals("no newlines", LibraryFormats.toPlainText("data.json", "no newlines"))
        // Text without CRLF passes through byte-for-byte.
        val md = "# Heading\n\nBody text."
        assertEquals(md, LibraryFormats.toPlainText("doc.markdown", md))
    }
}
