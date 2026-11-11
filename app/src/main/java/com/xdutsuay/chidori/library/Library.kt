package com.xdutsuay.chidori.library

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Process-wide Library: one database, one indexer, and the chat tool bound to them. */
object Library {
    private const val DB_NAME = "library.db"

    @Volatile private var storeRef: LibraryStore? = null
    @Volatile private var indexerRef: LibraryIndexer? = null
    @Volatile private var hasDocs = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun store(context: Context): LibraryStore = storeRef ?: synchronized(this) {
        storeRef ?: LibraryStore(
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration(true)
                .build(),
        ).also { store ->
            storeRef = store
            scope.launch { hasDocs = store.hasDocuments() }
        }
    }

    fun indexer(context: Context): LibraryIndexer = indexerRef ?: synchronized(this) {
        indexerRef ?: store(context).let { store ->
            LibraryIndexer(context, store) { hasDocs = store.hasDocuments() }
        }.also { indexerRef = it }
    }

    fun searchTool(context: Context): LibrarySearchTool {
        val store = store(context)
        return LibrarySearchTool(
            search = { query, limit -> runBlocking(Dispatchers.IO) { store.search(query, limit) } },
            hasDocuments = { hasDocs },
        )
    }
}
