package com.xdutsuay.chidori.computer.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure-Kotlin model of one agent action on the Computer (COS), shown in the
 * Computer screen's Activity tab. Deliberately free of Android imports so it
 * can be unit-tested on the JVM.
 */
enum class Kind { COMMAND, SCREENSHOT, GUI }

/**
 * How the recorded action ended.
 *
 * [OK] means the tool finished successfully (exit code 0 / capture succeeded);
 * [ERROR] covers non-zero exit codes and thrown failures (see [ActivityEntry.detail]);
 * [DENIED] means the user (or confirm gate) did not approve the call;
 * [CANCELLED] means Chat Stop terminated it.
 */
enum class Outcome { OK, ERROR, DENIED, CANCELLED }

/**
 * One chronological agent-activity entry.
 *
 * [summary] is a short single-line description (collapsed command text, the GUI
 * action, or blank for screenshots — a blank [summary] means the UI shows the
 * [Kind] label instead). [detail] carries extra outcome data: the exit code
 * (via [exitDetail]) or a failure message, null when there is nothing to add.
 */
data class ActivityEntry(
    val id: Long,
    val atMs: Long,
    val kind: Kind,
    val summary: String,
    val outcome: Outcome,
    val detail: String? = null,
)

/**
 * Detail text for a non-zero exit code, e.g. `exit 127`. Pure data formatting
 * shared by the tool hooks so the "exit N" shape lives in exactly one place.
 */
fun exitDetail(code: Int): String = "exit $code"

/**
 * Bounded in-memory agent activity log. Newest entries first; adding beyond
 * [capacity] drops the oldest. Thread-safe ([Synchronized] mutators) because
 * tool calls run on arbitrary worker threads while the UI collects [entries].
 *
 * @param capacity maximum number of retained entries; coerced up to [MIN_CAPACITY].
 * @param clock wall-clock source for [ActivityEntry.atMs]; injectable for tests.
 */
class ComputerActivityLog(
    capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val maxEntries = capacity.coerceAtLeast(MIN_CAPACITY)
    private val ring = ArrayDeque<ActivityEntry>(maxEntries)
    private val mutableEntries = MutableStateFlow<List<ActivityEntry>>(emptyList())

    /** Snapshot of retained entries, newest first; emits on every change. */
    val entries: StateFlow<List<ActivityEntry>> = mutableEntries.asStateFlow()

    private var nextId: Long = 1L

    /**
     * Appends an entry (newest position) and drops the oldest beyond capacity.
     * Returns the recorded entry, whose [ActivityEntry.id] is used by
     * [ComputerActivity.attachImage] to associate screenshot bytes with it.
     */
    @Synchronized
    fun record(kind: Kind, summary: String, outcome: Outcome, detail: String? = null): ActivityEntry {
        val entry = ActivityEntry(id = nextId++, atMs = clock(), kind = kind, summary = summary, outcome = outcome, detail = detail)
        ring.addFirst(entry)
        while (ring.size > maxEntries) ring.removeLast()
        mutableEntries.value = ring.toList()
        return entry
    }

    /** Removes every entry (ids keep counting up; a cleared log never reuses ids). */
    @Synchronized
    fun clear() {
        ring.clear()
        mutableEntries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        const val MIN_CAPACITY = 1

        /** Default max length the tool hooks pass to [ActivitySummary.forCommand]. */
        const val SUMMARY_MAX_LEN = 120
    }
}

/** Pure single-line summarization helpers for tool arguments. */
object ActivitySummary {

    /**
     * Collapses [cmd] to a single line (every whitespace run becomes one space,
     * ends trimmed) and truncates it with an ellipsis when longer than [maxLen].
     *
     * Edge cases: [maxLen] <= 0 returns ""; a result that fits is returned
     * unchanged (never ellipsized); a truncated result is exactly [maxLen]
     * characters long (ellipsis char included); an all-whitespace input
     * collapses to "".
     */
    fun forCommand(cmd: String, maxLen: Int): String {
        if (maxLen <= 0) return ""
        val collapsed = cmd.replace(WHITESPACE, " ").trim()
        if (collapsed.length <= maxLen) return collapsed
        return collapsed.take(maxLen - 1) + ELLIPSIS
    }

    private val WHITESPACE = Regex("\\s+")
    private const val ELLIPSIS = "…"
}

/**
 * Process-wide access point: tools record into [log] from anywhere, and the
 * Computer screen's Activity panel reads it. Also keeps a small bounded store
 * of screenshot bytes keyed by entry id so the panel can show thumbnails even
 * after the chat loop consumed the image from the tool attachment store.
 */
object ComputerActivity {
    val log = ComputerActivityLog()

    private const val IMAGE_CAPACITY = 8
    private val images = LinkedHashMap<Long, ByteArray>()

    /** Stores [bytes] as the thumbnail source for [entryId]; oldest dropped beyond capacity. */
    @Synchronized
    fun attachImage(entryId: Long, bytes: ByteArray) {
        images[entryId] = bytes
        while (images.size > IMAGE_CAPACITY) images.remove(images.keys.first())
    }

    /** Bytes previously attached for [entryId], or null (never attached / dropped). */
    @Synchronized
    fun imageFor(entryId: Long): ByteArray? = images[entryId]

    private fun clearImages() = images.clear()

    /** Clears both the log and the stored screenshot images. */
    fun clear() {
        log.clear()
        clearImages()
    }
}
