package com.druk.lmplayground.checkin

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.druk.lmplayground.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenItemMigrationTest {
    @Test
    fun migrationFromV9CreatesAUsableTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "open-items-mig"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        first.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE IF EXISTS `open_items`")
            execSQL("PRAGMA user_version = 9")
        }
        first.close()

        val second = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_9_10)
            .allowMainThreadQueries()
            .build()
        val item = sample("row-1")
        second.openItemDao().insert(item)
        val loaded = second.openItemDao().getById("row-1")
        assertEquals("Ship the migration", loaded?.summary)
        assertEquals("OPEN", loaded?.status)
        assertEquals("chat", loaded?.source)
        second.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun freshDatabaseInsertsAndRepositoryLeavesParkedAlone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = OpenItemRepository(db.openItemDao())
        val item = sample("row-2")
        repo.upsertCaptured(item)
        repo.applyUserAction(listOf("row-2"), CheckInUserAction.PARK, 50)
        repo.upsertCaptured(item.copy(summary = "should not resurrect", updatedAt = 60))
        val loaded = repo.getById("row-2")
        assertEquals(OpenItemStatus.PARKED.name, loaded?.status)
        assertEquals("Ship the migration", loaded?.summary)
        repo.applyUserAction(listOf("row-2"), CheckInUserAction.LATER, 70)
        assertEquals(OpenItemStatus.PARKED.name, repo.getById("row-2")?.status)
        assertTrue(db.openHelper.writableDatabase.version >= 10)
        db.close()
    }

    private fun sample(id: String) = OpenItemEntity(
        id = id,
        source = "chat",
        sourceId = "src-$id",
        sessionId = "session",
        kind = "DECISION",
        summary = "Ship the migration",
        probeJson = """{"type":"chat","sessionId":"session"}""",
        status = "OPEN",
        lastProbeState = "open",
        initialProbeState = "open",
        fingerprint = "fp-$id",
        fresh = false,
        createdAt = 1,
        updatedAt = 1,
    )
}
