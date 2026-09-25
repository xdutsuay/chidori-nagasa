package com.druk.lmplayground.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseCloudSchemaTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `new session defaults to LOCAL with null cloud fields`() = runBlocking {
        val session = ChatSessionEntity(
            id = "s1",
            title = "t",
            modelFilename = "m.gguf",
            modelName = "M",
            createdAt = 1,
            updatedAt = 1,
        )
        db.chatDao().insertSession(session)
        val loaded = db.chatDao().getSession("s1")!!
        assertEquals(ChatRoute.LOCAL, loaded.route)
        assertNull(loaded.cloudProviderId)
        assertNull(loaded.cloudModel)
    }
}
