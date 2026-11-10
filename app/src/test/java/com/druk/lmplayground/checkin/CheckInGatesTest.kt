package com.druk.lmplayground.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * One test per gate, plus the order rule that pause wins before quiet hours
 * and before a resolved probe is applied.
 */
class CheckInGatesTest {
    private val now = 1_700_000_000_000L

    @Test
    fun pauseStopsBeforeQuietHoursAndProbe() {
        val result = CheckInGates.evaluate(
            base(
                paused = true,
                minuteOfDay = 23 * 60,
                items = listOf(item(fresh = true)),
                probes = mapOf("1" to ProbeSnapshot("done", resolved = true, reason = "already")),
            ),
        )
        assertEquals(CheckInStop.PAUSED_OR_DISABLED, result.stop)
        assertTrue(result.toNotify.isEmpty())
        assertTrue(result.markDone.isEmpty())
    }

    @Test
    fun disabledStopsTheSameWay() {
        val result = CheckInGates.evaluate(base(enabled = false, items = listOf(item(fresh = true))))
        assertEquals(CheckInStop.PAUSED_OR_DISABLED, result.stop)
        assertTrue(result.toNotify.isEmpty())
    }

    @Test
    fun quietHoursIncludingOvernightWindow() {
        assertTrue(CheckInGates.isQuiet(23 * 60, 22 * 60, 8 * 60))
        assertTrue(CheckInGates.isQuiet(7 * 60, 22 * 60, 8 * 60))
        assertFalse(CheckInGates.isQuiet(12 * 60, 22 * 60, 8 * 60))
        assertTrue(CheckInGates.isQuiet(22 * 60, 22 * 60, 8 * 60))
        assertFalse(CheckInGates.isQuiet(8 * 60, 22 * 60, 8 * 60))
        assertFalse(CheckInGates.isQuiet(3 * 60, 0, 0))
        val result = CheckInGates.evaluate(
            base(minuteOfDay = 23 * 60, quietStartMinute = 22 * 60, quietEndMinute = 8 * 60, items = listOf(item(fresh = true))),
        )
        assertEquals(CheckInStop.QUIET_HOURS, result.stop)
    }

    @Test
    fun minuteOfDayUsesTheGivenZone() {
        val ms = Instant.parse("2026-01-01T22:30:00Z").toEpochMilli()
        assertEquals(22 * 60 + 30, CheckInGates.minuteOfDay(ms, ZoneId.of("UTC")))
    }

    @Test
    fun inactivityThreshold() {
        assertTrue(CheckInGates.inactiveLongEnough(10_000, null, 5_000))
        assertTrue(CheckInGates.inactiveLongEnough(10_000, 5_000, 5_000))
        assertFalse(CheckInGates.inactiveLongEnough(10_000, 5_001, 5_000))
        val result = CheckInGates.evaluate(
            base(lastActivityAtMs = now - 1_000, inactivityThresholdMs = 60_000, items = listOf(item(fresh = true))),
        )
        assertEquals(CheckInStop.NOT_INACTIVE_LONG_ENOUGH, result.stop)
    }

    @Test
    fun resolvedProbeBecomesDoneAndIsNotNotified() {
        val result = CheckInGates.evaluate(
            base(
                items = listOf(item(id = "a", fresh = true), item(id = "b")),
                probes = mapOf("a" to ProbeSnapshot("completed", resolved = true, reason = "run completed")),
            ),
        )
        assertEquals(listOf("a"), result.markDone.map { it.id })
        assertEquals("run completed", result.markDone.single().reason)
        assertTrue(result.toNotify.none { it.id == "a" })
    }

    @Test
    fun newsFilterStateChangeDueWindowAndFresh() {
        val changed = CheckInGates.evaluate(
            base(
                items = listOf(item(initial = "open", fresh = false)),
                probes = mapOf("1" to ProbeSnapshot("moved", resolved = false)),
            ),
        )
        assertEquals(listOf("1"), changed.toNotify.map { it.id })

        val unchanged = CheckInGates.evaluate(
            base(
                items = listOf(item(initial = "open", fresh = false)),
                probes = mapOf("1" to ProbeSnapshot("open", resolved = false)),
            ),
        )
        assertEquals(CheckInStop.NOTHING_TO_RAISE, unchanged.stop)

        val due = CheckInGates.evaluate(
            base(
                items = listOf(item(initial = "scheduled", fresh = false, dueAtMs = now + 60_000)),
                probes = mapOf("1" to ProbeSnapshot("scheduled", resolved = false)),
            ),
        )
        assertEquals(listOf("1"), due.toNotify.map { it.id })

        val far = CheckInGates.evaluate(
            base(
                items = listOf(item(initial = "scheduled", fresh = false, dueAtMs = now + 48 * 3_600_000)),
                probes = mapOf("1" to ProbeSnapshot("scheduled", resolved = false)),
            ),
        )
        assertTrue(far.toNotify.isEmpty())

        val fresh = CheckInGates.evaluate(
            base(
                items = listOf(item(initial = "needs_you:", fresh = true)),
                probes = mapOf("1" to ProbeSnapshot("needs_you:", resolved = false)),
            ),
        )
        assertEquals(listOf("1"), fresh.toNotify.map { it.id })
    }

    @Test
    fun dedupeSkipsRaisedUnansweredUnchangedEvenIfDue() {
        val result = CheckInGates.evaluate(
            base(
                items = listOf(
                    item(
                        status = OpenItemStatus.RAISED,
                        initial = "same",
                        stateAtLastRaise = "same",
                        lastRaisedAtMs = now - 25 * 3_600_000,
                        dueAtMs = now + 60_000,
                        fresh = false,
                    ),
                ),
                probes = mapOf("1" to ProbeSnapshot("same", resolved = false)),
            ),
        )
        assertTrue(CheckInGates.qualifiesAsNews(result.let { item(
            status = OpenItemStatus.RAISED,
            initial = "same",
            stateAtLastRaise = "same",
            lastRaisedAtMs = now - 25 * 3_600_000,
            dueAtMs = now + 60_000,
            fresh = false,
        ) }, ProbeSnapshot("same", resolved = false), now))
        assertFalse(CheckInGates.passesDedupe(item(
            status = OpenItemStatus.RAISED,
            initial = "same",
            stateAtLastRaise = "same",
        ), ProbeSnapshot("same", resolved = false)))
        assertTrue(result.toNotify.isEmpty())
    }

    @Test
    fun raisedItemWithANewProbeStateIsNotDeduped() {
        val result = CheckInGates.evaluate(
            base(
                items = listOf(
                    item(
                        status = OpenItemStatus.RAISED,
                        initial = "needs_you:",
                        stateAtLastRaise = "needs_you:",
                        lastRaisedAtMs = now - 1_000,
                        fresh = false,
                    ),
                ),
                probes = mapOf("1" to ProbeSnapshot("needs_you:blocked", resolved = false)),
            ),
        )
        assertEquals(listOf("1"), result.toNotify.map { it.id })
    }

    @Test
    fun parkedAndClosedAreNeverRaised() {
        val result = CheckInGates.evaluate(
            base(
                items = listOf(
                    item(id = "p", status = OpenItemStatus.PARKED, fresh = true, dueAtMs = now),
                    item(id = "c", status = OpenItemStatus.CLOSED, fresh = true, dueAtMs = now),
                ),
                probes = mapOf(
                    "p" to ProbeSnapshot("x", resolved = false),
                    "c" to ProbeSnapshot("y", resolved = true, reason = "nope"),
                ),
            ),
        )
        assertTrue(result.toNotify.isEmpty())
        assertTrue(result.markDone.isEmpty())
        assertTrue(result.probedIds.isEmpty())
    }

    @Test
    fun perRunCapBundlesAtMostThree() {
        val items = (1..5).map { item(id = "$it", fresh = true, createdAtMs = it.toLong()) }
        val result = CheckInGates.evaluate(
            base(items = items, probes = items.associate { it.id to ProbeSnapshot("open", false) }),
        )
        assertNull(result.stop)
        assertEquals(listOf("1", "2", "3"), result.toNotify.map { it.id })
    }

    @Test
    fun dailyCapBlocksTheNudgeButStillMarksResolvedDone() {
        val result = CheckInGates.evaluate(
            base(
                notificationsSentToday = 1,
                dailyCap = 1,
                items = listOf(item(id = "n", fresh = true), item(id = "d", fresh = true)),
                probes = mapOf(
                    "n" to ProbeSnapshot("open", false),
                    "d" to ProbeSnapshot("completed", resolved = true, reason = "run completed"),
                ),
            ),
        )
        assertEquals(CheckInStop.DAILY_CAP, result.stop)
        assertTrue(result.toNotify.isEmpty())
        assertEquals(listOf("d"), result.markDone.map { it.id })
    }

    private fun base(
        paused: Boolean = false,
        enabled: Boolean = true,
        minuteOfDay: Int = 12 * 60,
        quietStartMinute: Int = 22 * 60,
        quietEndMinute: Int = 8 * 60,
        lastActivityAtMs: Long? = now - 3 * 3_600_000,
        inactivityThresholdMs: Long = 2 * 3_600_000,
        items: List<CheckInItem> = emptyList(),
        probes: Map<String, ProbeSnapshot> = emptyMap(),
        notificationsSentToday: Int = 0,
        dailyCap: Int = 4,
    ) = CheckInInput(
        nowMs = now,
        paused = paused,
        enabled = enabled,
        minuteOfDay = minuteOfDay,
        quietStartMinute = quietStartMinute,
        quietEndMinute = quietEndMinute,
        lastActivityAtMs = lastActivityAtMs,
        inactivityThresholdMs = inactivityThresholdMs,
        items = items,
        probes = probes,
        notificationsSentToday = notificationsSentToday,
        dailyCap = dailyCap,
    )

    private fun item(
        id: String = "1",
        status: OpenItemStatus = OpenItemStatus.OPEN,
        dueAtMs: Long? = null,
        lastRaisedAtMs: Long? = null,
        initial: String = "open",
        stateAtLastRaise: String? = null,
        fresh: Boolean = false,
        createdAtMs: Long = 1,
    ) = CheckInItem(
        id = id,
        status = status,
        dueAtMs = dueAtMs,
        lastRaisedAtMs = lastRaisedAtMs,
        initialProbeState = initial,
        stateAtLastRaise = stateAtLastRaise,
        fresh = fresh,
        summary = "item $id",
        createdAtMs = createdAtMs,
    )
}
