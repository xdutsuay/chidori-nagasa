package com.druk.lmplayground.checkin

import java.time.Instant
import java.time.ZoneId

/**
 * Pure check-in gate pipeline (KMA-333). [evaluate] applies the gates in
 * the approved order. An early stop does not look at probes, so a paused
 * or quiet run cannot mark items DONE and cannot raise them.
 *
 * The foreground check lives on [CheckInRunner], in front of this pipeline.
 * It is a worker constraint, not an extra gate.
 */
object CheckInGates {
    const val DUE_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
    const val PER_RUN_ITEM_CAP: Int = 3

    fun evaluate(input: CheckInInput): CheckInResult {
        preProbeStop(input)?.let { stop ->
            return CheckInResult(stop, emptyList(), emptyList(), emptyList())
        }
        val done = mutableListOf<DoneUpdate>()
        val qualifying = mutableListOf<CheckInItem>()
        val probedIds = mutableListOf<String>()
        for (item in input.items) {
            if (!isActionable(item.status)) continue
            probedIds += item.id
            val probe = input.probes[item.id]
                ?: ProbeSnapshot(state = item.initialProbeState, resolved = false)
            if (probe.resolved) {
                done += DoneUpdate(item.id, probe.reason ?: "resolved")
                continue
            }
            if (!qualifiesAsNews(item, probe, input.nowMs)) continue
            if (!passesDedupe(item, probe)) continue
            qualifying += item
        }
        qualifying.sortWith(
            compareBy<CheckInItem> { it.dueAtMs ?: Long.MAX_VALUE }.thenBy { it.createdAtMs },
        )
        if (qualifying.isEmpty()) {
            return CheckInResult(CheckInStop.NOTHING_TO_RAISE, emptyList(), done, probedIds)
        }
        if (input.notificationsSentToday >= input.dailyCap) {
            return CheckInResult(CheckInStop.DAILY_CAP, emptyList(), done, probedIds)
        }
        val picked = qualifying.take(PER_RUN_ITEM_CAP.coerceAtMost(input.perRunItemCap))
        return CheckInResult(null, picked, done, probedIds)
    }

    /** Gates 1–3. Null means the run may ingest and probe. */
    fun preProbeStop(input: CheckInInput): CheckInStop? {
        if (!gateEnabled(input.paused, input.enabled)) return CheckInStop.PAUSED_OR_DISABLED
        if (isQuiet(input.minuteOfDay, input.quietStartMinute, input.quietEndMinute)) {
            return CheckInStop.QUIET_HOURS
        }
        if (!inactiveLongEnough(input.nowMs, input.lastActivityAtMs, input.inactivityThresholdMs)) {
            return CheckInStop.NOT_INACTIVE_LONG_ENOUGH
        }
        return null
    }

    /** Gate 1. Both the global pause and the check-in toggle must allow work. */
    fun gateEnabled(paused: Boolean, enabled: Boolean): Boolean = !paused && enabled

    /**
     * Gate 2, inverted: true when [minuteOfDay] falls inside quiet hours.
     * A window with [startMinute] == [endMinute] is disabled. A window that
     * crosses midnight (start > end) covers the evening and the morning.
     */
    fun isQuiet(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return false
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    /**
     * Gate 3. No recorded activity counts as inactive. Equal to the threshold
     * is long enough.
     */
    fun inactiveLongEnough(nowMs: Long, lastActivityAtMs: Long?, thresholdMs: Long): Boolean {
        if (thresholdMs <= 0L) return true
        val last = lastActivityAtMs ?: return true
        return nowMs - last >= thresholdMs
    }

    /** Gate 7, plus DONE / ANSWERED which are not re-checked. */
    fun isActionable(status: OpenItemStatus): Boolean =
        status == OpenItemStatus.OPEN || status == OpenItemStatus.RAISED

    /**
     * Gate 5. News is a probe-state change since the last raise (or since
     * capture, if it has never been raised), a due date inside the next 24h
     * that has not been raised in that window, or a fresh first sighting.
     * Overdue items count as due.
     */
    fun qualifiesAsNews(item: CheckInItem, probe: ProbeSnapshot, nowMs: Long): Boolean {
        val changedSinceRaise = item.lastRaisedAtMs != null && probe.state != item.stateAtLastRaise
        val changedSinceCapture = item.lastRaisedAtMs == null && probe.state != item.initialProbeState
        val firstSighting = item.fresh && item.lastRaisedAtMs == null
        return changedSinceRaise || changedSinceCapture || isDueSoon(item, nowMs) || firstSighting
    }

    fun isDueSoon(item: CheckInItem, nowMs: Long): Boolean {
        val due = item.dueAtMs ?: return false
        if (due - nowMs > DUE_WINDOW_MS) return false
        val raised = item.lastRaisedAtMs ?: return true
        return nowMs - raised >= DUE_WINDOW_MS
    }

    /**
     * Gate 6. A RAISED item is still unanswered. Skip it when the probe state
     * matches the state we last raised (or the capture baseline if that is
     * missing). A state change is allowed through.
     */
    fun passesDedupe(item: CheckInItem, probe: ProbeSnapshot): Boolean {
        if (item.status != OpenItemStatus.RAISED) return true
        val baseline = item.stateAtLastRaise ?: item.initialProbeState
        return probe.state != baseline
    }

    fun minuteOfDay(nowMs: Long, zone: ZoneId): Int {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        return local.hour * 60 + local.minute
    }

    fun dayKey(nowMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
}

/** Everything [CheckInGates.evaluate] needs. Built by the runner, not by Room. */
data class CheckInInput(
    val nowMs: Long,
    val paused: Boolean,
    val enabled: Boolean,
    val minuteOfDay: Int,
    val quietStartMinute: Int,
    val quietEndMinute: Int,
    val lastActivityAtMs: Long?,
    val inactivityThresholdMs: Long,
    val items: List<CheckInItem>,
    val probes: Map<String, ProbeSnapshot>,
    val notificationsSentToday: Int,
    val dailyCap: Int,
    val perRunItemCap: Int = CheckInGates.PER_RUN_ITEM_CAP,
)
