package com.druk.lmplayground.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface IndexState {
    data object Idle : IndexState
    data class Indexing(val sourceName: String, val filesDone: Int, val filesTotal: Int?) : IndexState
    data class Done(val files: Int, val skipped: Int) : IndexState
    data class Failed(val message: String) : IndexState
}

/** Reads user-picked files/folders (Storage Access Framework) into the [LibraryStore]. */
class LibraryIndexer(context: Context, private val store: LibraryStore, private val onChanged: suspend () -> Unit) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val lock = Mutex()
    private val _state = MutableStateFlow<IndexState>(IndexState.Idle)
    val state: StateFlow<IndexState> = _state

    suspend fun addAndIndex(uri: Uri, isFolder: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = displayName(uri, isFolder)
            val id = store.addSource(uri.toString(), name, isFolder)
            report { indexSource(id, uri, isFolder, name) }
        }
    }

    suspend fun reindexAll() = withContext(Dispatchers.IO) {
        lock.withLock {
            report {
                var total = Counts()
                store.sources().forEach { s -> total += indexSource(s.id, Uri.parse(s.uri), s.isFolder, s.displayName) }
                total
            }
        }
    }

    suspend fun remove(sourceId: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            store.sources().firstOrNull { it.id == sourceId }?.let { s ->
                runCatching { resolver.releasePersistableUriPermission(Uri.parse(s.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            store.removeSource(sourceId)
            onChanged()
        }
    }

    private suspend fun report(block: suspend () -> Counts) {
        try {
            val c = block()
            _state.value = IndexState.Done(c.files, c.skipped)
        } catch (e: Exception) {
            _state.value = IndexState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            onChanged()
        }
    }

    private data class Counts(val files: Int = 0, val skipped: Int = 0) {
        operator fun plus(o: Counts) = Counts(files + o.files, skipped + o.skipped)
    }

    private suspend fun indexSource(sourceId: Long, uri: Uri, isFolder: Boolean, name: String): Counts {
        _state.value = IndexState.Indexing(name, 0, null)
        val files = if (isFolder) {
            val root = DocumentFile.fromTreeUri(appContext, uri) ?: error("folder is not readable")
            mutableListOf<DocumentFile>().also { collect(root, 0, it) }
        } else {
            listOfNotNull(DocumentFile.fromSingleUri(appContext, uri))
        }
        var done = 0
        var skipped = 0
        val kept = mutableSetOf<String>()
        files.forEach { f ->
            val fileName = f.name.orEmpty()
            val size = f.length()
            if (!LibraryFormats.isSupported(fileName) || size > MAX_FILE_BYTES) {
                skipped++
            } else {
                val docUri = f.uri.toString()
                kept += docUri
                val modified = f.lastModified()
                if (store.needsIndex(docUri, size, modified)) {
                    val raw = resolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (raw == null) {
                        skipped++
                        kept -= docUri
                    } else {
                        val text = LibraryFormats.toPlainText(fileName, raw)
                        store.replaceDocument(sourceId, docUri, fileName, size, modified, LibraryChunker.chunk(text))
                    }
                }
                done++
            }
            _state.value = IndexState.Indexing(name, done, files.size)
        }
        store.removeDocumentsNotIn(sourceId, kept)
        return Counts(done, skipped)
    }

    private fun collect(dir: DocumentFile, depth: Int, out: MutableList<DocumentFile>) {
        if (depth > MAX_DEPTH) return
        for (child in dir.listFiles()) {
            if (out.size >= MAX_FILES) return
            when {
                child.isDirectory -> if (!child.name.orEmpty().startsWith(".")) collect(child, depth + 1, out)
                child.isFile -> out += child
            }
        }
    }

    private fun displayName(uri: Uri, isFolder: Boolean): String {
        val doc = if (isFolder) DocumentFile.fromTreeUri(appContext, uri) else DocumentFile.fromSingleUri(appContext, uri)
        return doc?.name ?: uri.lastPathSegment.orEmpty()
    }

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_FILES = 2000
        const val MAX_DEPTH = 8
    }
}
