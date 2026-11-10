package com.druk.lmplayground.library

import androidx.room.withTransaction

/** Sources, documents and searchable parts of the Library. */
class LibraryStore(private val db: LibraryDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.dao()

    suspend fun addSource(uri: String, displayName: String, isFolder: Boolean): Long =
        db.withTransaction {
            dao.sourceByUri(uri)?.id
                ?: dao.insertSource(LibrarySourceEntity(uri = uri, displayName = displayName, isFolder = isFolder, addedAt = clock()))
        }

    suspend fun sources(): List<LibrarySource> = dao.sources()

    suspend fun needsIndex(uri: String, sizeBytes: Long, lastModified: Long): Boolean {
        val doc = dao.docByUri(uri) ?: return true
        return doc.sizeBytes != sizeBytes || doc.lastModified != lastModified
    }

    suspend fun replaceDocument(
        sourceId: Long,
        uri: String,
        name: String,
        sizeBytes: Long,
        lastModified: Long,
        chunks: List<String>,
    ) = db.withTransaction {
        val now = clock()
        val existing = dao.docByUri(uri)
        val docId = if (existing != null) {
            dao.deleteChunksOfDoc(existing.id)
            dao.updateDoc(existing.id, sourceId, name, sizeBytes, lastModified, now)
            existing.id
        } else {
            dao.insertDoc(
                LibraryDocEntity(
                    sourceId = sourceId, uri = uri, name = name,
                    sizeBytes = sizeBytes, lastModified = lastModified, indexedAt = now,
                ),
            )
        }
        dao.insertChunks(chunks.mapIndexed { i, text -> LibraryChunkEntity(docId = docId, ordinal = i, text = text) })
    }

    suspend fun removeDocumentsNotIn(sourceId: Long, keepUris: Set<String>) = db.withTransaction {
        dao.docsOfSource(sourceId).filter { it.uri !in keepUris }.forEach { deleteDoc(it.id) }
    }

    suspend fun removeSource(sourceId: Long) = db.withTransaction {
        dao.docsOfSource(sourceId).forEach { deleteDoc(it.id) }
        dao.deleteSource(sourceId)
    }

    suspend fun hasDocuments(): Boolean = dao.hasDocuments()

    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<LibraryHit> {
        val match = LibraryQuery.toFtsMatch(query) ?: return emptyList()
        val terms = LibraryQuery.terms(query)
        val n = limit.coerceIn(1, MAX_LIMIT)
        return dao.match(match, CANDIDATE_CAP)
            .map { LibraryHit(it.docName, it.ordinal + 1, it.text, score(it.text, terms)) }
            .sortedWith(compareByDescending<LibraryHit> { it.score }.thenBy { it.docName }.thenBy { it.part })
            .take(n)
    }

    private suspend fun deleteDoc(docId: Long) {
        dao.deleteChunksOfDoc(docId)
        dao.deleteDoc(docId)
    }

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 10
        private const val CANDIDATE_CAP = 200

        fun score(text: String, terms: List<String>): Int {
            val lower = text.lowercase()
            return terms.sumOf { term ->
                var count = 0
                var i = lower.indexOf(term)
                while (i >= 0) {
                    count++
                    i = lower.indexOf(term, i + term.length)
                }
                count
            }
        }
    }
}
