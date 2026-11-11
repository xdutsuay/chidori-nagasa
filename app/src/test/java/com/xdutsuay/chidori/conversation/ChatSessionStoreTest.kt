package com.xdutsuay.chidori.conversation

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xdutsuay.chidori.data.AppDatabase
import com.xdutsuay.chidori.data.ChatRepository
import com.xdutsuay.chidori.data.ChatRoute
import com.xdutsuay.chidori.data.ConversationMetadata
import com.xdutsuay.chidori.data.SystemPromptRepository
import com.xdutsuay.chidori.models.ModelInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ChatSessionStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: ChatSessionStore
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ChatSessionStore(
            ChatRepository(db.chatDao()),
            SystemPromptRepository(db.systemPromptDao()),
            ChatImageStore(app),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun model() = ModelInfo(
        name = "Test Model",
        filename = "test-model.gguf",
        description = "test"
    )

    private fun runCreateSession(
        firstMessage: String = "hello world",
        systemPrompt: String = "be brief",
    ): String = runBlocking {
        store.createSession(
            Message(author = "User", content = firstMessage),
            model(),
            GenerationParams(),
            systemPrompt,
        )
    }

    /** Creates a session with an explicit route (used to seed cloud-routed rows). */
    private fun runCreateSessionWithRoute(
        route: String,
        cloudProviderId: String? = null,
        cloudModel: String? = null,
    ): String = runBlocking {
        store.createSession(
            Message(author = "User", content = "hello world"),
            model(),
            GenerationParams(),
            "be brief",
            route = route,
            cloudProviderId = cloudProviderId,
            cloudModel = cloudModel,
        )
    }

    @Test
    fun createSessionPersistsRowWithTruncatedTitle() = runBlocking {
        val longMessage = "x".repeat(80)
        val id = runCreateSession(firstMessage = longMessage)

        val entity = store.getSession(id)
        assertNotNull(entity)
        assertEquals(50, entity!!.title.length)
        assertEquals("test-model.gguf", entity.modelFilename)
        assertEquals("Test Model", entity.modelName)
        assertEquals("be brief", entity.systemPrompt)
        assertEquals(GenerationParams().contextSize, entity.contextSize)
    }

    @Test
    fun persistAndLoadMessagesRoundTrip() = runBlocking {
        val id = runCreateSession()
        store.persistMessage(id, Message(author = "User", content = "q1", responseTokens = 0))
        store.persistMessage(
            id,
            Message(
                author = "Assistant",
                content = "a1",
                thinkingDurationSeconds = 3,
                thinkingTokens = 42,
                responseTokens = 100,
                responseDurationSeconds = 1.5f,
            )
        )

        val messages = store.loadSessionMessages(id)!!
        assertEquals(2, messages.size)
        assertEquals("q1", messages[0].content)
        val assistant = messages[1]
        assertEquals("a1", assistant.content)
        assertEquals(3, assistant.thinkingDurationSeconds)
        assertEquals(42, assistant.thinkingTokens)
        assertEquals(100, assistant.responseTokens)
        assertEquals(1.5f, assistant.responseDurationSeconds)
        assertNull(assistant.imageUri)
    }

    @Test
    fun persistAndLoadRestoresToolCalls() = runBlocking {
        val id = runCreateSession()
        // User row: no tool calls — must stay null after round-trip.
        store.persistMessage(id, Message(author = "User", content = "q"))
        store.persistMessage(
            id,
            Message(
                author = "Assistant",
                content = "done",
                toolCalls = listOf(
                    ToolCallInfo(
                        name = "web_search",
                        arguments = "{\"query\":\"a\"}",
                        result = "{\"hits\":2}",
                        durationMs = 800L,
                        precedingThinking = "searching",
                        precedingThinkingDurationSeconds = 1,
                        precedingThinkingTokens = 9,
                    ),
                    ToolCallInfo(
                        name = "cos_exec",
                        arguments = "{\"cmd\":\"ls\"}",
                        result = "{\"cancelled\":true}",
                        durationMs = 100L,
                        stopped = true,
                    ),
                ),
            )
        )

        val messages = store.loadSessionMessages(id)!!
        assertEquals(2, messages.size)
        assertNull(messages[0].toolCalls)

        val restored = messages[1].toolCalls!!
        assertEquals(2, restored.size)
        assertEquals("web_search", restored[0].name)
        assertEquals("{\"query\":\"a\"}", restored[0].arguments)
        assertEquals("{\"hits\":2}", restored[0].result)
        assertEquals(800L, restored[0].durationMs)
        assertEquals("searching", restored[0].precedingThinking)
        assertEquals(1, restored[0].precedingThinkingDurationSeconds)
        assertEquals(9, restored[0].precedingThinkingTokens)
        assertFalse(restored[0].stopped)
        assertEquals("cos_exec", restored[1].name)
        assertTrue(restored[1].stopped)
        assertEquals("{\"cancelled\":true}", restored[1].result)
    }

    @Test
    fun loadDropsImageUriWhenFileIsGone() = runBlocking {
        val id = runCreateSession()
        store.persistMessage(
            id,
            Message(author = "User", content = "look"),
            imagePath = File(app.filesDir, "chat_images/gone.jpg").absolutePath,
        )
        val messages = store.loadSessionMessages(id)!!
        assertNull(messages[0].imageUri)
    }

    @Test
    fun deleteSessionRemovesRowsAndImageFiles() = runBlocking {
        val imagesDir = File(app.filesDir, "chat_images").apply { mkdirs() }
        val image = File(imagesDir, "todelete.jpg").apply { writeBytes(byteArrayOf(1)) }

        val id = runCreateSession()
        store.persistMessage(id, Message(author = "User", content = "q"), image.absolutePath)

        store.deleteSession(id)

        assertNull(store.getSession(id))
        assertTrue(store.loadSessionMessages(id)!!.isEmpty())
        assertFalse(image.exists())
    }

    @Test
    fun persistWebLinksWritesMetadataAndSkipsEmpty() = runBlocking {
        val id = runCreateSession()

        // Empty snapshot: no metadata written (entity keeps its "{}" default).
        store.persistWebLinks(id, emptyMap())
        assertEquals("{}", store.getSession(id)!!.metadata)

        store.persistWebLinks(id, mapOf("1" to "https://a", "2" to "https://b"))
        val links = ConversationMetadata.parse(store.getSession(id)!!.metadata)
            .getStringMap(ConversationMetadata.KEY_WEB_LINKS)
        assertEquals(mapOf("1" to "https://a", "2" to "https://b"), links)
    }

    @Test
    fun systemPromptCreateFindUpdate() = runBlocking {
        val created = store.createSystemPrompt("You are terse.")
        assertNotNull(created)

        assertEquals(created!!.id, store.findSystemPromptByText("You are terse.")?.id)

        assertTrue(store.updateSystemPromptText(created.id, "You are verbose."))
        assertEquals("You are verbose.", store.findSystemPromptByText("You are verbose.")?.text)
        assertNull(store.findSystemPromptByText("You are terse."))

        // Unknown id: no-op, reports false.
        assertFalse(store.updateSystemPromptText("missing-id", "whatever"))
    }

    @Test
    fun nullRepositoriesDegradeGracefully() = runBlocking {
        val nullStore = ChatSessionStore(null, null, ChatImageStore(app))
        assertNull(nullStore.loadSessionMessages("any"))
        assertNull(nullStore.getSession("any"))
        assertFalse(nullStore.systemPromptsAvailable)
        assertNull(nullStore.createSystemPrompt("text"))
        // Mutations are silent no-ops.
        nullStore.persistMessage("any", Message(author = "User", content = "q"))
        nullStore.deleteSession("any")
    }

    @Test
    fun switchSessionToCloudKeepsMessagesAndSetsFields() = runBlocking {
        val id = runCreateSession()
        store.persistMessage(id, Message(author = "User", content = "q1"))
        store.persistMessage(id, Message(author = "Assistant", content = "a1", responseTokens = 7))

        store.switchSessionToCloud(id, "provider-1", "gpt-test-model", "GPT Test Model")

        val entity = store.getSession(id)
        assertNotNull(entity)
        assertEquals(ChatRoute.CLOUD, entity!!.route)
        assertEquals("provider-1", entity.cloudProviderId)
        assertEquals("gpt-test-model", entity.cloudModel)
        assertEquals("gpt-test-model", entity.modelFilename)
        assertEquals("GPT Test Model", entity.modelName)

        // Messages are untouched by the route switch.
        val messages = store.loadSessionMessages(id)!!
        assertEquals(2, messages.size)
        assertEquals("q1", messages[0].content)
        assertEquals("User", messages[0].author)
        assertEquals("a1", messages[1].content)
        assertEquals("Assistant", messages[1].author)
        assertEquals(7, messages[1].responseTokens)
    }

    @Test
    fun switchSessionToLocalClearsCloudFields() = runBlocking {
        val id = runCreateSessionWithRoute(
            route = ChatRoute.CLOUD,
            cloudProviderId = "provider-1",
            cloudModel = "gpt-test-model",
        )
        // Precondition: the seeded row really is cloud-routed.
        val before = store.getSession(id)!!
        assertEquals(ChatRoute.CLOUD, before.route)
        assertEquals("provider-1", before.cloudProviderId)
        assertEquals("gpt-test-model", before.cloudModel)

        store.switchSessionToLocal(id, "local-model.gguf", "Local Model")

        val entity = store.getSession(id)
        assertNotNull(entity)
        assertEquals(ChatRoute.LOCAL, entity!!.route)
        assertNull(entity.cloudProviderId)
        assertNull(entity.cloudModel)
        assertEquals("local-model.gguf", entity.modelFilename)
        assertEquals("Local Model", entity.modelName)
    }

    @Test
    fun switchSessionRoundTripLocalCloudLocal() = runBlocking {
        val id = runCreateSession()
        store.persistMessage(id, Message(author = "User", content = "q1"))
        store.persistMessage(id, Message(author = "Assistant", content = "a1"))

        store.switchSessionToCloud(id, "provider-9", "cloud-model-x", "Cloud Model X")
        val cloud = store.getSession(id)!!
        assertEquals(ChatRoute.CLOUD, cloud.route)
        assertEquals("provider-9", cloud.cloudProviderId)
        assertEquals("cloud-model-x", cloud.modelFilename)

        store.switchSessionToLocal(id, "test-model.gguf", "Test Model")
        val local = store.getSession(id)!!
        assertEquals(ChatRoute.LOCAL, local.route)
        assertNull(local.cloudProviderId)
        assertNull(local.cloudModel)
        assertEquals("test-model.gguf", local.modelFilename)
        assertEquals("Test Model", local.modelName)

        val messages = store.loadSessionMessages(id)!!
        assertEquals(2, messages.size)
        assertEquals("q1", messages[0].content)
        assertEquals("a1", messages[1].content)
    }

    @Test
    fun switchSessionLeavesOtherColumnsUnchanged() = runBlocking {
        val id = runCreateSession(firstMessage = "title source", systemPrompt = "be brief")
        store.pinSession(id, true)
        store.persistWebLinks(id, mapOf("1" to "https://example.com/a"))
        store.updateSessionParams(id, GenerationParams(temperature = 0.25f))

        val before = store.getSession(id)!!

        store.switchSessionToCloud(id, "provider-1", "cloud-model-y", "Cloud Model Y")
        val afterCloud = store.getSession(id)!!
        assertEquals(before.title, afterCloud.title)
        assertEquals(before.systemPrompt, afterCloud.systemPrompt)
        assertEquals(before.createdAt, afterCloud.createdAt)
        assertEquals(before.pinned, afterCloud.pinned)
        assertEquals(before.metadata, afterCloud.metadata)
        assertEquals(before.contextSize, afterCloud.contextSize)
        assertEquals(before.temperature, afterCloud.temperature)
        assertEquals(before.topP, afterCloud.topP)
        assertEquals(before.repetitionPenalty, afterCloud.repetitionPenalty)
        assertEquals(before.topK, afterCloud.topK)
        assertEquals(before.minP, afterCloud.minP)
        assertEquals(before.seed, afterCloud.seed)
        assertEquals(before.thinkingBudget, afterCloud.thinkingBudget)

        store.switchSessionToLocal(id, "test-model.gguf", "Test Model")
        val afterLocal = store.getSession(id)!!
        assertEquals(before.title, afterLocal.title)
        assertEquals(before.systemPrompt, afterLocal.systemPrompt)
        assertEquals(before.createdAt, afterLocal.createdAt)
        assertEquals(before.pinned, afterLocal.pinned)
        assertEquals(before.metadata, afterLocal.metadata)
        assertEquals(before.contextSize, afterLocal.contextSize)
        assertEquals(before.temperature, afterLocal.temperature)
        assertEquals(before.topP, afterLocal.topP)
        assertEquals(before.repetitionPenalty, afterLocal.repetitionPenalty)
        assertEquals(before.topK, afterLocal.topK)
        assertEquals(before.minP, afterLocal.minP)
        assertEquals(before.seed, afterLocal.seed)
        assertEquals(before.thinkingBudget, afterLocal.thinkingBudget)
    }

    @Test
    fun switchSessionUnknownIdIsNoOp() = runBlocking {
        val id = runCreateSession()
        val before = store.getSession(id)!!
        val rowsBefore = chatSessionRowCount()

        store.switchSessionToCloud("missing-id", "provider-1", "cloud-model-z", "Cloud Z")
        store.switchSessionToLocal("missing-id", "local.gguf", "Local")

        // No row created for the unknown id; existing row untouched; row count unchanged.
        assertNull(store.getSession("missing-id"))
        assertEquals(before, store.getSession(id))
        assertEquals(rowsBefore, chatSessionRowCount())
    }

    /** Direct row count against the in-memory DB, independent of the store API. */
    private fun chatSessionRowCount(): Long =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM chat_sessions")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
}
