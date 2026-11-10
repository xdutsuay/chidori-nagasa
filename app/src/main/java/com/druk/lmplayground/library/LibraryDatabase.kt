package com.druk.lmplayground.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "library_sources", indices = [Index(value = ["uri"], unique = true)])
data class LibrarySourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val isFolder: Boolean,
    val addedAt: Long,
)

@Entity(
    tableName = "library_docs",
    indices = [Index(value = ["uri"], unique = true), Index(value = ["sourceId"])],
)
data class LibraryDocEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val indexedAt: Long,
)

@Entity(tableName = "library_chunks", indices = [Index(value = ["docId"])])
data class LibraryChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val ordinal: Int,
    val text: String,
)

@Fts4(contentEntity = LibraryChunkEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "library_chunks_fts")
data class LibraryChunkFts(@ColumnInfo(name = "text") val text: String)

data class LibrarySource(
    val id: Long,
    val uri: String,
    val displayName: String,
    val isFolder: Boolean,
    val documentCount: Int,
    val chunkCount: Int,
)

data class LibraryChunkRow(val docName: String, val ordinal: Int, val text: String)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_sources WHERE uri = :uri")
    suspend fun sourceByUri(uri: String): LibrarySourceEntity?

    @Insert
    suspend fun insertSource(source: LibrarySourceEntity): Long

    @Query(
        """SELECT s.id, s.uri, s.displayName, s.isFolder,
        (SELECT COUNT(*) FROM library_docs d WHERE d.sourceId = s.id) AS documentCount,
        (SELECT COUNT(*) FROM library_chunks c JOIN library_docs d ON c.docId = d.id WHERE d.sourceId = s.id) AS chunkCount
        FROM library_sources s ORDER BY s.displayName"""
    )
    suspend fun sources(): List<LibrarySource>

    @Query("SELECT * FROM library_docs WHERE uri = :uri")
    suspend fun docByUri(uri: String): LibraryDocEntity?

    @Query("SELECT * FROM library_docs WHERE sourceId = :sourceId")
    suspend fun docsOfSource(sourceId: Long): List<LibraryDocEntity>

    @Insert
    suspend fun insertDoc(doc: LibraryDocEntity): Long

    @Query(
        """UPDATE library_docs SET sourceId = :sourceId, name = :name, sizeBytes = :sizeBytes,
        lastModified = :lastModified, indexedAt = :indexedAt WHERE id = :id"""
    )
    suspend fun updateDoc(id: Long, sourceId: Long, name: String, sizeBytes: Long, lastModified: Long, indexedAt: Long)

    @Insert
    suspend fun insertChunks(chunks: List<LibraryChunkEntity>)

    @Query("DELETE FROM library_chunks WHERE docId = :docId")
    suspend fun deleteChunksOfDoc(docId: Long)

    @Query("DELETE FROM library_docs WHERE id = :docId")
    suspend fun deleteDoc(docId: Long)

    @Query("DELETE FROM library_sources WHERE id = :sourceId")
    suspend fun deleteSource(sourceId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM library_docs)")
    suspend fun hasDocuments(): Boolean

    @Query(
        """SELECT d.name AS docName, c.ordinal AS ordinal, c.text AS text
        FROM library_chunks_fts f
        JOIN library_chunks c ON c.id = f.rowid
        JOIN library_docs d ON d.id = c.docId
        WHERE library_chunks_fts MATCH :match LIMIT :cap"""
    )
    suspend fun match(match: String, cap: Int): List<LibraryChunkRow>
}

@Database(
    entities = [LibrarySourceEntity::class, LibraryDocEntity::class, LibraryChunkEntity::class, LibraryChunkFts::class],
    version = 1,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
