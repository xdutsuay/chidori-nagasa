package com.druk.lmplayground.checkin

import com.druk.lmplayground.data.ChatMessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A chat row that was just written. [ChatSessionStore] reports these. */
data class PersistedChat(
    val sessionId: String,
    val author: String,
    val content: String,
    val messageId: Long,
    val timestamp: Long,
)

/**
 * Process-wide hook so chat persistence does not take a dependency on the
 * check-in graph. [App] installs [onPersisted] after Room is ready.
 */
object OpenItemHooks {
    @Volatile
    var onPersisted: ((PersistedChat) -> Unit)? = null
}

/** Transcripts the model could not read yet. Retried by the check-in run, then dropped. */
data class PendingExtract(
    val sessionId: String,
    val transcript: String,
    val createdAt: Long,
)

interface PendingExtractStore {
    fun load(): List<PendingExtract>
    fun save(items: List<PendingExtract>)
}

/**
 * Chat capture: commands update the ledger immediately, and an idle assistant
 * turn is extracted with [completer] after [idleDelayMs]. Extraction runs
 * only while check-ins are enabled, so an opt-out user does not pay for a
 * model call. Explicit commands still update the ledger when the toggle is off.
 */
class OpenItemCapture(
    private val scope: CoroutineScope,
    private val repository: OpenItemWriter,
    private val sessionCommands: SessionCommandWriter,
    private val messages: suspend (String) -> List<ChatMessageEntity>,
    private val completer: CheckInCompleter,
    private val enabled: () -> Boolean,
    private val pending: PendingExtractStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    var idleDelayMs: Long = IDLE_DELAY_MS,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    fun onPersisted(event: PersistedChat) {
        scope.launch {
            try {
                handle(event)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Capture must not take down the chat save path.
            }
        }
    }

    internal suspend fun handle(event: PersistedChat) {
        // Any new message means the session is not idle. Drop a pending extraction.
        jobs.remove(event.sessionId)?.cancel()
        if (event.author.equals("User", ignoreCase = true)) {
            when (parseChatCommand(event.content)) {
                ChatCommand.DONE ->
                    sessionCommands.markLatest(event.sessionId, OpenItemStatus.DONE, "chat command", clock())
                ChatCommand.PARK ->
                    sessionCommands.markLatest(event.sessionId, OpenItemStatus.PARKED, null, clock())
                ChatCommand.CLOSE ->
                    sessionCommands.markLatest(event.sessionId, OpenItemStatus.CLOSED, null, clock())
                ChatCommand.REMIND -> addReminder(event)
                null -> sessionCommands.markRaisedAnswered(event.sessionId, clock())
            }
            return
        }
        if (!event.author.equals("Assistant", ignoreCase = true)) return
        if (!enabled()) return
        jobs[event.sessionId] = scope.launch {
            delay(idleDelayMs)
            jobs.remove(event.sessionId)
            if (!enabled()) return@launch
            extractSession(event.sessionId)
        }
    }

    suspend fun extractSession(sessionId: String) {
        val transcript = transcriptFor(sessionId) ?: return
        val raw = completer.complete(CheckInGrammar.prompt(transcript))
        if (raw == null) {
            rememberPending(sessionId, transcript)
            return
        }
        storeParsed(sessionId, OpenItemExtractor.parse(raw))
    }

    /**
     * Retry transcripts the idle pass could not complete. A null model keeps
     * the row. Malformed JSON is dropped. Rows older than [PENDING_TTL_MS]
     * are dropped.
     */
    suspend fun drainPending() {
        if (!enabled()) return
        val now = clock()
        val kept = mutableListOf<PendingExtract>()
        for (row in pending.load()) {
            if (now - row.createdAt > PENDING_TTL_MS) continue
            val raw = completer.complete(CheckInGrammar.prompt(row.transcript))
            if (raw == null) {
                kept += row
                continue
            }
            storeParsed(row.sessionId, OpenItemExtractor.parse(raw))
        }
        pending.save(kept.takeLast(MAX_PENDING))
    }

    private suspend fun addReminder(event: PersistedChat) {
        val now = clock()
        val summary = reminderSummary(event)
        val sourceId = "remind:${event.sessionId}:$now"
        val fingerprint = openItemFingerprint(
            OpenItemSource.CHAT.storageValue(),
            sourceId,
            OpenItemKind.WAITING_ON_USER.name,
            summary,
        )
        repository.upsertCaptured(
            OpenItemEntity(
                id = UUID.randomUUID().toString(),
                source = OpenItemSource.CHAT.storageValue(),
                sourceId = sourceId,
                sessionId = event.sessionId,
                sourceMessageId = event.messageId,
                kind = OpenItemKind.WAITING_ON_USER.name,
                summary = summary,
                dueAt = now,
                probeJson = ProbeDescriptor(
                    type = ProbeDescriptor.TYPE_DUE_DATE,
                    sessionId = event.sessionId,
                ).toJson(),
                status = OpenItemStatus.OPEN.name,
                lastProbeState = "overdue",
                initialProbeState = "overdue",
                fingerprint = fingerprint,
                fresh = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun reminderSummary(event: PersistedChat): String {
        val normalized = event.content.trim()
        val generic = normalized.equals("remind me", ignoreCase = true) ||
            normalized.equals("remind me about this", ignoreCase = true)
        if (!generic) {
            return normalized.removePrefix("remind me ").removePrefix("Remind me ").trim().ifBlank { normalized }
        }
        val previous = messages(event.sessionId)
            .asReversed()
            .firstOrNull { it.content.isNotBlank() && parseChatCommand(it.content) == null }
        return previous?.content?.trim()?.take(280)?.ifBlank { null } ?: "Reminder from this chat"
    }

    private suspend fun transcriptFor(sessionId: String): String? {
        val lines = messages(sessionId).takeLast(12)
            .filter { it.content.isNotBlank() }
            .joinToString("\n") { "${it.author}: ${it.content}" }
            .take(CheckInGrammar.MAX_TRANSCRIPT_CHARS)
        return lines.ifBlank { null }
    }

    private suspend fun storeParsed(sessionId: String, parsed: List<OpenItemExtractor.ExtractedItem>?) {
        if (parsed == null) return
        val now = clock()
        for (item in parsed) {
            val fingerprint = openItemFingerprint(
                OpenItemSource.CHAT.storageValue(),
                sessionId,
                item.kind.name,
                item.summary,
            )
            val due = item.dueAtMs
            val probeType = if (due != null) ProbeDescriptor.TYPE_DUE_DATE else ProbeDescriptor.TYPE_CHAT
            val initial = if (due != null) "scheduled" else "open"
            repository.upsertCaptured(
                OpenItemEntity(
                    id = UUID.randomUUID().toString(),
                    source = OpenItemSource.CHAT.storageValue(),
                    sourceId = fingerprint,
                    sessionId = sessionId,
                    kind = item.kind.name,
                    summary = item.summary,
                    dueAt = due,
                    probeJson = ProbeDescriptor(type = probeType, sessionId = sessionId).toJson(),
                    status = OpenItemStatus.OPEN.name,
                    lastProbeState = initial,
                    initialProbeState = initial,
                    fingerprint = fingerprint,
                    fresh = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun rememberPending(sessionId: String, transcript: String) {
        val now = clock()
        val next = pending.load().toMutableList()
        next += PendingExtract(sessionId, transcript, now)
        pending.save(next.takeLast(MAX_PENDING))
    }

    companion object {
        const val IDLE_DELAY_MS = 60_000L
        const val PENDING_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        const val MAX_PENDING = 5
    }
}

/** SharedPreferences backing for [PendingExtractStore], same file as check-in settings. */
class PreferencesPendingStore(context: android.content.Context) : PendingExtractStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        CheckInPreferences.PREFS,
        android.content.Context.MODE_PRIVATE,
    )

    override fun load(): List<PendingExtract> {
        val raw = prefs.getString(CheckInPreferences.KEY_PENDING, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val sessionId = obj.optString("sessionId")
                val transcript = obj.optString("transcript")
                if (sessionId.isBlank() || transcript.isBlank()) continue
                add(
                    PendingExtract(
                        sessionId = sessionId,
                        transcript = transcript,
                        createdAt = obj.optLong("createdAt"),
                    ),
                )
            }
        }
    }

    override fun save(items: List<PendingExtract>) {
        val array = JSONArray()
        for (item in items.takeLast(OpenItemCapture.MAX_PENDING)) {
            array.put(
                JSONObject()
                    .put("sessionId", item.sessionId)
                    .put("transcript", item.transcript)
                    .put("createdAt", item.createdAt),
            )
        }
        prefs.edit().putString(CheckInPreferences.KEY_PENDING, array.toString()).apply()
    }
}
