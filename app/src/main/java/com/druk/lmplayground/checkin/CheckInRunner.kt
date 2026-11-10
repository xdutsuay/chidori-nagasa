package com.druk.lmplayground.checkin

import java.time.ZoneId

/**
 * One check-in pass with the side effects (ingest, probe, ledger, notify)
 * injected. Gates stay in [CheckInGates]. Foreground is refused before
 * those gates so a visible app does not probe the desktop.
 *
 * Pause, quiet hours, and the inactivity threshold return before [ingest],
 * so a blocked run does not touch the network.
 */
class CheckInRunner(
    private val nowMs: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val readConfig: () -> RunnerConfig,
    private val ledger: OpenItemLedger,
    private val lastActivityAtMs: suspend () -> Long?,
    private val ingest: suspend (Long) -> Unit,
    private val probe: suspend (List<OpenItemEntity>, Long) -> Map<String, ProbeSnapshot>,
    private val notify: suspend (List<OpenItemEntity>, String) -> Boolean,
    private val recordNotification: (String) -> Unit,
    private val wording: suspend (List<OpenItemEntity>) -> String = { CheckInCopy.bundle(it) },
) {
    suspend fun run(): CheckInRunOutcome {
        val now = nowMs()
        val config = readConfig()
        val lastActivity = lastActivityAtMs()
        val input = CheckInInput(
            nowMs = now,
            paused = config.paused,
            enabled = config.enabled,
            minuteOfDay = CheckInGates.minuteOfDay(now, zone),
            quietStartMinute = config.quietStartMinute,
            quietEndMinute = config.quietEndMinute,
            lastActivityAtMs = lastActivity,
            inactivityThresholdMs = config.inactivityThresholdMs,
            items = emptyList(),
            probes = emptyMap(),
            notificationsSentToday = config.notificationsSentToday,
            dailyCap = config.dailyCap,
        )
        val early = CheckInGates.preProbeStop(input)
        if (early != null) {
            return CheckInRunOutcome(CheckInResult(early, emptyList(), emptyList(), emptyList()), ingested = false, notified = false)
        }
        if (config.foreground) {
            return CheckInRunOutcome(
                CheckInResult(CheckInStop.NOTHING_TO_RAISE, emptyList(), emptyList(), emptyList()),
                ingested = false,
                notified = false,
                skippedForeground = true,
            )
        }
        ingest(now)
        val items = ledger.listActionable()
        val probes = probe(items, now)
        val result = CheckInGates.evaluate(
            input.copy(
                items = items.map { it.toCheckInItem() },
                probes = probes,
            ),
        )
        val byId = items.associateBy { it.id }
        val chosen = result.toNotify.mapNotNull { byId[it.id] }
        val posted = chosen.isNotEmpty() && notify(chosen, wording(chosen))
        val applied = if (posted) result else result.copy(toNotify = emptyList())
        ledger.applyResult(applied, probes, now)
        if (posted) {
            recordNotification(CheckInGates.dayKey(now, zone))
        }
        return CheckInRunOutcome(result = applied, ingested = true, notified = posted)
    }
}

/** Snapshot of settings the runner needs. Android prefs implement this in [CheckInRuntime]. */
data class RunnerConfig(
    val enabled: Boolean,
    val paused: Boolean,
    val foreground: Boolean,
    val quietStartMinute: Int,
    val quietEndMinute: Int,
    val inactivityThresholdMs: Long,
    val dailyCap: Int,
    val notificationsSentToday: Int,
)

data class CheckInRunOutcome(
    val result: CheckInResult,
    val ingested: Boolean,
    val notified: Boolean,
    val skippedForeground: Boolean = false,
)

/** Deterministic nudge text. The model may replace the first line; this is the fallback. */
object CheckInCopy {
    fun bundle(items: List<OpenItemEntity>): String =
        items.joinToString("\n") { "• ${it.summary}" }

    fun wordingPrompt(items: List<OpenItemEntity>): String = buildString {
        appendLine("Write one short sentence telling the user what needs them. No greeting. No list.")
        appendLine(bundle(items))
    }

    fun notificationText(modelLine: String?, items: List<OpenItemEntity>): String {
        val fallback = bundle(items)
        val line = modelLine?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (line.isBlank() || line.length > 180 || line.contains('{') || line.contains('}')) {
            return fallback
        }
        return line + "\n" + fallback
    }
}
