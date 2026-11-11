package com.xdutsuay.chidori.library

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Contract tests for [LibraryStore] on an in-memory Room database (Robolectric),
 * following the ChatSessionStoreTest pattern: build with
 * `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()` and drive
 * the suspend API via [runBlocking].
 *
 * Covers: source idempotency, ordered source listing with counts, needsIndex
 * staleness detection, replaceDocument re-index without duplicate hits,
 * removeDocumentsNotIn, removeSource, hasDocuments, and search ranking
 * (score = query-term occurrence count, ties by docName then part, 1-based
 * parts, limit coerced to 1..10, null FTS match -> empty results).
 */
@RunWith(RobolectricTestRunner::class)
class LibraryStoreTest {

    private lateinit var app: Application
    private lateinit var db: LibraryDatabase
    private lateinit var store: LibraryStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = LibraryStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun runBlock(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun source(sourceId: Long): LibrarySource? =
        store.sources().firstOrNull { it.id == sourceId }

    /** Adds a source and indexes one document into it; returns the source id. */
    private suspend fun seedDocument(
        sourceName: String,
        uri: String,
        docName: String,
        chunks: List<String>,
        sourceUri: String = "content://docs/$sourceName",
    ): Long {
        val sourceId = store.addSource(sourceUri, sourceName, isFolder = false)
        store.replaceDocument(sourceId, uri, docName, sizeBytes = 100, lastModified = 200, chunks = chunks)
        return sourceId
    }

    // ---- addSource ----

    @Test
    fun addSourceIsIdempotentByUri() = runBlock {
        val first = store.addSource("content://a", "Alpha", isFolder = false)
        val second = store.addSource("content://a", "Alpha", isFolder = false)
        assertEquals("same uri must return the existing id", first, second)
        assertEquals(1, store.sources().size)
    }

    @Test
    fun addSourcePersistsDisplayNameAndFolderFlag() = runBlock {
        val folderId = store.addSource("content://folder", "My Folder", isFolder = true)
        val fileSource = store.addSource("content://file", "My File", isFolder = false)
        val sources = store.sources()
        assertEquals(2, sources.size)
        val folder = sources.first { it.id == folderId }
        val file = sources.first { it.id == fileSource }
        assertEquals("My Folder", folder.displayName)
        assertTrue(folder.isFolder)
        assertEquals("My File", file.displayName)
        assertFalse(file.isFolder)
        assertEquals(0, folder.documentCount)
        assertEquals(0, folder.chunkCount)
    }

    // ---- sources ----

    @Test
    fun sourcesAreOrderedByDisplayName() = runBlock {
        store.addSource("content://z", "Zulu", isFolder = false)
        store.addSource("content://a", "Alpha", isFolder = false)
        store.addSource("content://m", "Mike", isFolder = false)
        assertEquals(listOf("Alpha", "Mike", "Zulu"), store.sources().map { it.displayName })
    }

    @Test
    fun sourcesReportDocumentAndChunkCounts() = runBlock {
        val sourceId = store.addSource("content://counts", "Counted", isFolder = false)
        store.replaceDocument(sourceId, "content://doc/1", "one.txt", 10, 20, listOf("chunk a", "chunk b"))
        store.replaceDocument(sourceId, "content://doc/2", "two.txt", 30, 40, listOf("c1", "c2", "c3"))
        val src = source(sourceId)!!
        assertEquals(2, src.documentCount)
        assertEquals(5, src.chunkCount)

        // Re-indexing one document replaces its chunks in the count.
        store.replaceDocument(sourceId, "content://doc/1", "one.txt", 10, 21, listOf("only"))
        assertEquals(2, source(sourceId)!!.documentCount)
        assertEquals(4, source(sourceId)!!.chunkCount)
    }

    // ---- needsIndex ----

    @Test
    fun needsIndexTrueForUnknownDocumentAndStaleMetadata() = runBlock {
        val sourceId = store.addSource("content://s", "S", isFolder = false)
        assertTrue(store.needsIndex("content://unknown", 100, 200))

        store.replaceDocument(sourceId, "content://known", "known.txt", 100, 200, listOf("text"))
        assertFalse(store.needsIndex("content://known", 100, 200))
        assertTrue("size change must require re-index", store.needsIndex("content://known", 101, 200))
        assertTrue("lastModified change must require re-index", store.needsIndex("content://known", 100, 201))
    }

    // ---- replaceDocument ----

    @Test
    fun replaceDocumentReplacesChunksWithoutDuplicateHits() = runBlock {
        val sourceId = store.addSource("content://re", "Reindexed", isFolder = false)
        val uri = "content://re/doc"
        store.replaceDocument(sourceId, uri, "doc.txt", 10, 20, listOf("zebra lives here", "zebra again"))

        val first = store.search("zebra")
        assertEquals(2, first.size)
        assertEquals(2, source(sourceId)!!.chunkCount)

        // Re-index the same doc uri with different chunks: old chunks are gone.
        store.replaceDocument(sourceId, uri, "doc.txt", 12, 30, listOf("lion sleeps tonight"))
        val second = store.search("zebra")
        assertTrue("old chunks must be gone after re-index", second.isEmpty())
        val lion = store.search("lion")
        assertEquals(1, lion.size)
        assertEquals("lion sleeps tonight", lion[0].text)
        assertEquals(1, source(sourceId)!!.chunkCount)

        // Same metadata no longer needs an index pass.
        assertFalse(store.needsIndex(uri, 12, 30))
    }

    // ---- removeDocumentsNotIn ----

    @Test
    fun removeDocumentsNotInDropsUnlistedDocsAndTheirChunks() = runBlock {
        val sourceId = store.addSource("content://prune", "Pruned", isFolder = false)
        store.replaceDocument(sourceId, "content://keep", "keep.txt", 1, 1, listOf("keepable zebra"))
        store.replaceDocument(sourceId, "content://drop-a", "drop-a.txt", 2, 2, listOf("zebra one"))
        store.replaceDocument(sourceId, "content://drop-b", "drop-b.txt", 3, 3, listOf("zebra two"))

        store.removeDocumentsNotIn(sourceId, keepUris = setOf("content://keep"))

        val hits = store.search("zebra")
        assertEquals(1, hits.size)
        assertEquals("keepable zebra", hits[0].text)
        assertEquals("keep.txt", hits[0].docName)
        assertEquals(1, source(sourceId)!!.documentCount)
        assertEquals(1, source(sourceId)!!.chunkCount)
        // Removed docs become unknown again -> re-index required.
        assertTrue(store.needsIndex("content://drop-a", 2, 2))
    }

    @Test
    fun removeDocumentsNotInOnlyTouchesItsOwnSource() = runBlock {
        val sourceA = store.addSource("content://a-src", "Source A", isFolder = false)
        val sourceB = store.addSource("content://b-src", "Source B", isFolder = false)
        store.replaceDocument(sourceA, "content://a/1", "a1.txt", 1, 1, listOf("shared token"))
        store.replaceDocument(sourceB, "content://b/1", "b1.txt", 1, 1, listOf("shared token"))

        store.removeDocumentsNotIn(sourceA, keepUris = setOf("content://a/1"))

        assertEquals(2, store.search("shared").size)
        assertEquals(1, source(sourceB)!!.documentCount)
    }

    // ---- removeSource ----

    @Test
    fun removeSourceRemovesSourceDocsAndChunks() = runBlock {
        val doomed = seedDocument("Doomed", "content://doomed/1", "doomed.txt", listOf("phantom zebra"))
        seedDocument("Survivor", "content://survivor/1", "survivor.txt", listOf("real zebra"))

        assertTrue(store.search("zebra").isNotEmpty())
        assertTrue(store.hasDocuments())

        store.removeSource(doomed)

        assertTrue("removed source must disappear from sources()", source(doomed) == null)
        assertTrue("removed doc must not be searchable", store.search("phantom").isEmpty())
        val survivor = store.search("zebra")
        assertEquals(1, survivor.size)
        assertEquals("survivor.txt", survivor[0].docName)
        assertTrue(store.hasDocuments())
    }

    // ---- hasDocuments ----

    @Test
    fun hasDocumentsTracksLifecycle() = runBlock {
        assertFalse("empty db has no documents", store.hasDocuments())
        val sourceId = store.addSource("content://h", "Has", isFolder = false)
        assertFalse("a source without documents is not 'has documents'", store.hasDocuments())
        store.replaceDocument(sourceId, "content://h/1", "h.txt", 1, 1, listOf("content here"))
        assertTrue(store.hasDocuments())
        store.removeSource(sourceId)
        assertFalse(store.hasDocuments())
    }

    // ---- search ----

    @Test
    fun searchWithNoFtsMatchReturnsEmptyList() = runBlock {
        seedDocument("D", "content://d/1", "d.txt", listOf("meaningful words here"))
        assertTrue(store.search("!!!").isEmpty())
        assertTrue(store.search("").isEmpty())
        assertTrue(store.search("   ").isEmpty())
        assertTrue(store.search("a *").isEmpty())
    }

    @Test
    fun searchRanksByTermOccurrenceCountCaseInsensitive() = runBlock {
        seedDocument("Ranks", "content://r/1", "two-terms.txt", listOf("alpha beta alpha"))
        seedDocument("Ranks", "content://r/2", "one-term.txt", listOf("alpha gamma"))

        val hits = store.search("alpha beta")
        assertEquals("both chunks match at least one term", 2, hits.size)
        assertEquals("two-term chunk outranks one-term chunk", "two-terms.txt", hits[0].docName)
        assertEquals("one-term.txt", hits[1].docName)
    }

    @Test
    fun searchCountsOccurrencesCaseInsensitively() = runBlock {
        seedDocument("Case", "content://c/1", "loud.txt", listOf("ALPHA Alpha alpha"))
        seedDocument("Case", "content://c/2", "quiet.txt", listOf("alpha"))

        val hits = store.search("Alpha")
        assertEquals(2, hits.size)
        assertEquals("loud.txt", hits[0].docName)
        assertEquals(3, hits[0].score)
        assertEquals("quiet.txt", hits[1].docName)
        assertEquals(1, hits[1].score)
    }

    @Test
    fun searchTiesBreakByDocNameThenPart() = runBlock {
        // Same score across docs -> docName order.
        seedDocument("Tie", "content://t/1", "b.txt", listOf("zebra here"))
        seedDocument("Tie", "content://t/2", "a.txt", listOf("zebra there"))
        val acrossDocs = store.search("zebra")
        assertEquals(2, acrossDocs.size)
        assertEquals("a.txt", acrossDocs[0].docName)
        assertEquals("b.txt", acrossDocs[1].docName)

        // Same score within one doc -> part order (1-based).
        store.replaceDocument(
            store.addSource("content://parts", "Parts", isFolder = false),
            "content://parts/1",
            "parts.txt",
            1,
            1,
            listOf("zebra first", "zebra second", "zebra third"),
        )
        val withinDoc = store.search("zebra", limit = 10).filter { it.docName == "parts.txt" }
        assertEquals(listOf(1, 2, 3), withinDoc.map { it.part })
    }

    @Test
    fun searchHitsCarryDocNameTextAndOneBasedPart() = runBlock {
        store.replaceDocument(
            store.addSource("content://parts2", "Parts2", isFolder = false),
            "content://parts2/1",
            "parts2.txt",
            1,
            1,
            listOf("alpha opens", "alpha middles", "alpha closes"),
        )
        val hits = store.search("middles")
        assertEquals(1, hits.size)
        val hit = hits[0]
        assertEquals("parts2.txt", hit.docName)
        assertEquals("alpha middles", hit.text)
        assertEquals("parts are 1-based", 2, hit.part)
        assertTrue("score counts the single occurrence", hit.score >= 1)
    }

    @Test
    fun searchLimitIsCoercedTo1To10() = runBlock {
        val sourceId = store.addSource("content://many", "Many", isFolder = false)
        (1..12).forEach { i ->
            store.replaceDocument(
                sourceId,
                "content://many/$i",
                "d%02d.txt".format(i),
                1,
                1,
                listOf("needle $i"),
            )
        }

        assertEquals("default limit is 5", 5, store.search("needle").size)
        assertEquals("limit above 10 is clamped to 10", 10, store.search("needle", 99).size)
        assertEquals("limit below 1 is clamped to 1", 1, store.search("needle", 0).size)
        assertEquals(1, store.search("needle", -5).size)
        assertEquals(7, store.search("needle", 7).size)

        // Clamped limits still respect the tie-break (docName) order.
        val first = store.search("needle", 99)
        assertEquals((1..10).map { "d%02d.txt".format(it) }, first.map { it.docName })
    }

    @Test
    fun searchOnEmptyStoreReturnsEmptyList() = runBlock {
        assertTrue(store.search("anything").isEmpty())
    }
}
