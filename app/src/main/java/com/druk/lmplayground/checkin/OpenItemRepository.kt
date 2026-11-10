package com.druk.lmplayground.checkin

import kotlinx.coroutines.flow.Flow

/**
 * Ledger of open items. Upsert keeps a PARKED, CLOSED, or DONE row from
 * coming back to life when capture sees the same source again.
 */
class OpenItemRepository(private val dao: OpenItemDao) :
    OpenItemLedger,
    OpenItemWriter,
    SessionCommandWriter {
    fun observeAll(): Flow<List<OpenItemEntity>> = dao.observeAll()

    override suspend fun listActionable(): List<OpenItemEntity> = dao.listActionable()

    suspend fun getById(id: String): OpenItemEntity? = dao.getById(id)

    override suspend fun upsertCaptured(item: OpenItemEntity) {
        val existing = dao.findBySource(item.source, item.sourceId)
            ?: dao.findByFingerprint(item.fingerprint)
        if (existing == null) {
            dao.insert(item)
            return
        }
        if (existing.status == OpenItemStatus.PARKED.name ||
            existing.status == OpenItemStatus.CLOSED.name ||
            existing.status == OpenItemStatus.DONE.name
        ) {
            return
        }
        dao.update(
            existing.copy(
                summary = item.summary,
                kind = item.kind,
                dueAt = item.dueAt ?: existing.dueAt,
                probeJson = item.probeJson,
                sessionId = item.sessionId ?: existing.sessionId,
                sourceMessageId = item.sourceMessageId ?: existing.sourceMessageId,
                fresh = existing.fresh || item.fresh,
                updatedAt = item.updatedAt,
            ),
        )
    }

    override suspend fun markResolvedBySource(source: String, sourceId: String, reason: String, nowMs: Long) {
        val existing = dao.findBySource(source, sourceId) ?: return
        if (!isAutoCloseable(existing.status)) return
        dao.update(
            existing.copy(
                status = OpenItemStatus.DONE.name,
                doneReason = reason,
                fresh = false,
                updatedAt = nowMs,
            ),
        )
    }

    override suspend fun markLatest(sessionId: String, status: OpenItemStatus, reason: String?, nowMs: Long) {
        val existing = dao.latestActionableForSession(sessionId) ?: return
        dao.update(
            existing.copy(
                status = status.name,
                doneReason = reason,
                fresh = false,
                updatedAt = nowMs,
            ),
        )
    }

    /** A user reply that is not a command means the raised items were answered. */
    override suspend fun markRaisedAnswered(sessionId: String, nowMs: Long) {
        for (existing in dao.raisedForSession(sessionId)) {
            dao.update(
                existing.copy(
                    status = OpenItemStatus.ANSWERED.name,
                    fresh = false,
                    updatedAt = nowMs,
                ),
            )
        }
    }

    suspend fun applyUserAction(ids: List<String>, action: CheckInUserAction, nowMs: Long) {
        for (id in ids) {
            val existing = dao.getById(id) ?: continue
            val updated = when (action) {
                CheckInUserAction.DONE -> existing.copy(
                    status = OpenItemStatus.DONE.name,
                    doneReason = "notification",
                    fresh = false,
                    updatedAt = nowMs,
                )
                CheckInUserAction.PARK -> existing.copy(
                    status = OpenItemStatus.PARKED.name,
                    fresh = false,
                    updatedAt = nowMs,
                )
                CheckInUserAction.CLOSE -> existing.copy(
                    status = OpenItemStatus.CLOSED.name,
                    fresh = false,
                    updatedAt = nowMs,
                )
                CheckInUserAction.LATER -> existing.copy(
                    status = OpenItemStatus.RAISED.name,
                    lastRaisedAt = nowMs,
                    stateAtLastRaise = existing.lastProbeState,
                    fresh = false,
                    updatedAt = nowMs,
                )
            }
            dao.update(updated)
        }
    }

    override suspend fun applyResult(
        result: CheckInResult,
        probes: Map<String, ProbeSnapshot>,
        nowMs: Long,
    ) {
        val notified = result.toNotify.map { it.id }.toSet()
        val done = result.markDone.associate { it.id to it.reason }
        for (id in result.probedIds) {
            val current = dao.getById(id) ?: continue
            val probe = probes[id]
            val reason = done[id]
            if (reason != null) {
                if (isAutoCloseable(current.status)) {
                    dao.update(
                        current.copy(
                            status = OpenItemStatus.DONE.name,
                            doneReason = reason,
                            fresh = false,
                            lastProbeState = probe?.state ?: current.lastProbeState,
                            updatedAt = nowMs,
                        ),
                    )
                }
                continue
            }
            if (id in notified && probe != null) {
                dao.update(
                    current.copy(
                        status = OpenItemStatus.RAISED.name,
                        lastRaisedAt = nowMs,
                        stateAtLastRaise = probe.state,
                        lastProbeState = probe.state,
                        fresh = false,
                        updatedAt = nowMs,
                    ),
                )
                continue
            }
            if (probe != null && probe.state != current.lastProbeState) {
                dao.update(current.copy(lastProbeState = probe.state, updatedAt = nowMs))
            }
        }
    }

    private fun isAutoCloseable(status: String): Boolean =
        status == OpenItemStatus.OPEN.name ||
            status == OpenItemStatus.RAISED.name ||
            status == OpenItemStatus.ANSWERED.name
}

/** Notification and list actions that change a row's status. */
enum class CheckInUserAction {
    DONE,
    PARK,
    CLOSE,
    LATER,
}

/** Read side the check-in runner needs. Room implements this; tests fake it. */
interface OpenItemLedger {
    suspend fun listActionable(): List<OpenItemEntity>
    suspend fun applyResult(result: CheckInResult, probes: Map<String, ProbeSnapshot>, nowMs: Long)
}

/** Session-scoped status changes from chat commands. */
interface SessionCommandWriter {
    suspend fun markLatest(sessionId: String, status: OpenItemStatus, reason: String?, nowMs: Long)
    suspend fun markRaisedAnswered(sessionId: String, nowMs: Long)
}

/** Write side capture and ingest share. */
interface OpenItemWriter {
    suspend fun upsertCaptured(item: OpenItemEntity)
    suspend fun markResolvedBySource(source: String, sourceId: String, reason: String, nowMs: Long)
}
