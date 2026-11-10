package com.druk.lmplayground.checkin

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Where an open item came from. Stored as the wire token ([storageValue]),
 * not the enum name, so the Room column stays stable if a constant is renamed.
 */
enum class OpenItemSource {
    CHAT,
    DESKTOP_RUN,
    TEAM_ROOM,
    ;

    fun storageValue(): String = when (this) {
        CHAT -> "chat"
        DESKTOP_RUN -> "desktop_run"
        TEAM_ROOM -> "team_room"
    }

    companion object {
        fun fromStorage(raw: String?): OpenItemSource? =
            entries.firstOrNull { it.storageValue() == raw }
    }
}

/** What kind of unfinished work the item is. */
enum class OpenItemKind {
    DECISION,
    DEADLINE,
    HALF_DONE,
    WAITING_ON_USER,
    ;

    companion object {
        fun fromStorage(raw: String?): OpenItemKind? =
            entries.firstOrNull { it.name == raw }
    }
}

/** Lifecycle of one ledger row. PARKED and CLOSED are never raised. */
enum class OpenItemStatus {
    OPEN,
    RAISED,
    ANSWERED,
    DONE,
    PARKED,
    CLOSED,
    ;

    companion object {
        fun fromStorage(raw: String?): OpenItemStatus? =
            entries.firstOrNull { it.name == raw }
    }
}

/**
 * How a later check-in re-probes an item. JSON so new probe kinds can land
 * without another Room migration. Unknown types stay unresolved.
 */
data class ProbeDescriptor(
    val type: String,
    val sessionId: String? = null,
    val instanceId: String? = null,
    val runId: String? = null,
    val fromRole: String? = null,
    val toRole: String? = null,
    val handoffKey: String? = null,
    val downloadFilename: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", type)
        putOpt("sessionId", sessionId)
        putOpt("instanceId", instanceId)
        putOpt("runId", runId)
        putOpt("fromRole", fromRole)
        putOpt("toRole", toRole)
        putOpt("handoffKey", handoffKey)
        putOpt("downloadFilename", downloadFilename)
    }.toString()

    companion object {
        const val TYPE_CHAT = "chat"
        const val TYPE_DESKTOP_RUN = "desktop_run"
        const val TYPE_TEAM_HANDOFF = "team_handoff"
        const val TYPE_DUE_DATE = "due_date"
        const val TYPE_DOWNLOAD = "download"
        const val TYPE_COS_JOB = "cos_job"

        fun fromJson(json: String?): ProbeDescriptor? {
            if (json.isNullOrBlank()) return null
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val type = obj.optString("type").takeIf { it.isNotBlank() } ?: return null
            return ProbeDescriptor(
                type = type,
                sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() },
                instanceId = obj.optString("instanceId").takeIf { it.isNotBlank() },
                runId = obj.optString("runId").takeIf { it.isNotBlank() },
                fromRole = obj.optString("fromRole").takeIf { it.isNotBlank() },
                toRole = obj.optString("toRole").takeIf { it.isNotBlank() },
                handoffKey = obj.optString("handoffKey").takeIf { it.isNotBlank() },
                downloadFilename = obj.optString("downloadFilename").takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * What a live re-probe saw. [resolved] means the underlying work is finished
 * and the row should become DONE without being mentioned.
 */
data class ProbeSnapshot(
    val state: String,
    val resolved: Boolean,
    val reason: String? = null,
)

/** One item the pure gate pipeline reasons about. No Room, no clock I/O. */
data class CheckInItem(
    val id: String,
    val status: OpenItemStatus,
    val dueAtMs: Long?,
    val lastRaisedAtMs: Long?,
    val initialProbeState: String,
    val stateAtLastRaise: String?,
    val fresh: Boolean,
    val summary: String,
    val createdAtMs: Long,
)

data class DoneUpdate(val id: String, val reason: String)

/**
 * Outcome of one gate pass. [toNotify] is empty whenever [stop] is set,
 * except that [markDone] is still filled when a probe resolved an item
 * and a later cap blocked the nudge.
 */
data class CheckInResult(
    val stop: CheckInStop?,
    val toNotify: List<CheckInItem>,
    val markDone: List<DoneUpdate>,
    val probedIds: List<String>,
)

enum class CheckInStop {
    PAUSED_OR_DISABLED,
    QUIET_HOURS,
    NOT_INACTIVE_LONG_ENOUGH,
    NOTHING_TO_RAISE,
    DAILY_CAP,
}

fun OpenItemEntity.toCheckInItem(): CheckInItem = CheckInItem(
    id = id,
    status = OpenItemStatus.fromStorage(status) ?: OpenItemStatus.CLOSED,
    dueAtMs = dueAt,
    lastRaisedAtMs = lastRaisedAt,
    initialProbeState = initialProbeState,
    stateAtLastRaise = stateAtLastRaise,
    fresh = fresh,
    summary = summary,
    createdAtMs = createdAt,
)

/**
 * Identity of an item. The same fingerprint is the same item, so capture
 * upserts instead of inserting a second row. [summary] is normalized; desktop
 * runs pass a stable token (`run:<id>`) so a live step line does not fork a
 * new row.
 */
fun openItemFingerprint(source: String, sourceId: String, kind: String, summary: String): String {
    val norm = summary.trim().lowercase().replace(Regex("\\s+"), " ")
    val raw = "$source|$sourceId|$kind|$norm"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
