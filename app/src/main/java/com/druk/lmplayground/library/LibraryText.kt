package com.druk.lmplayground.library

import org.jsoup.Jsoup
import kotlin.math.max
import kotlin.math.min

/** Splits document text into overlapping, search-sized parts. */
object LibraryChunker {
    const val CHUNK_CHARS = 1000
    const val OVERLAP_CHARS = 150

    fun chunk(text: String, chunkChars: Int = CHUNK_CHARS, overlapChars: Int = OVERLAP_CHARS): List<String> {
        require(chunkChars > 0 && overlapChars in 0 until chunkChars) { "invalid chunk sizes" }
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= chunkChars) return listOf(t)

        val out = mutableListOf<String>()
        var start = 0
        while (start < t.length) {
            while (start < t.length && t[start].isWhitespace()) start++
            if (start >= t.length) break
            var end = min(start + chunkChars, t.length)
            if (end < t.length) end = breakBefore(t, start + chunkChars / 2, end) ?: end
            val piece = t.substring(start, end).trim()
            if (piece.isNotEmpty()) out += piece
            if (end >= t.length) break
            start = max(end - overlapChars, start + 1)
        }
        return out
    }

    /** End index (exclusive) of the best boundary in [minEnd, limit], or null for a hard cut. */
    private fun breakBefore(t: String, minEnd: Int, limit: Int): Int? {
        val para = t.lastIndexOf("\n\n", limit - 2)
        if (para >= minEnd) return para + 2
        val line = t.lastIndexOf('\n', limit - 1)
        if (line >= minEnd) return line + 1
        val space = t.lastIndexOf(' ', limit - 1)
        if (space >= minEnd) return space + 1
        return null
    }
}

/** Which files the Library indexes, and how their bytes become plain text. */
object LibraryFormats {
    val TEXT_EXTENSIONS: Set<String> = setOf(
        "txt", "text", "md", "markdown", "rst", "org", "csv", "tsv", "json", "jsonl", "html", "htm", "log",
        "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "tex",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "cpp", "cc", "h", "hpp",
        "cs", "swift", "rb", "php", "sh", "bash", "ps1", "sql", "gradle", "css", "scss",
    )
    private val HTML_EXTENSIONS = setOf("html", "htm")

    fun extensionOf(fileName: String): String? {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1).lowercase()
    }

    fun isSupported(fileName: String): Boolean = extensionOf(fileName) in TEXT_EXTENSIONS

    fun toPlainText(fileName: String, raw: String): String {
        if (extensionOf(fileName) in HTML_EXTENSIONS) {
            val doc = Jsoup.parse(raw)
            doc.select("script, style, noscript").remove()
            return doc.text()
        }
        return raw.replace("\r\n", "\n")
    }
}

/** Turns a free-text question into a safe SQLite FTS MATCH expression. */
object LibraryQuery {
    const val MAX_TERMS = 12
    private const val MIN_TERM_LEN = 2
    private val TERM = Regex("""[\p{L}\p{N}\p{M}]+""")

    fun terms(userQuery: String): List<String> =
        TERM.findAll(userQuery)
            .map { it.value.lowercase() }
            .filter { it.length >= MIN_TERM_LEN }
            .distinct()
            .take(MAX_TERMS)
            .toList()

    fun toFtsMatch(userQuery: String): String? {
        val t = terms(userQuery)
        if (t.isEmpty()) return null
        return t.joinToString(" OR ") { "\"$it\"" }
    }
}

data class LibraryHit(val docName: String, val part: Int, val text: String, val score: Int)
