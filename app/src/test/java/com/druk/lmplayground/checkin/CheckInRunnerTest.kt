package com.druk.lmplayground.checkin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInRunnerTest {
    @Test
    fun pausedDoesNotIngestOrNotify() = runBlocking {
        var ingested = false
        var notified = 0
        val runner = runner(
            config = config(paused = true),
            ingest = { ingested = true },
            notify = { _, _ -> notified++ },
        )
        val outcome = runner.run()
        assertEquals(CheckInStop.PAUSED_OR_DISABLED, outcome.result.stop)
        assertFalse(ingested)
        assertFalse(outcome.notified)
        assertEquals(0, notified)
    }

    @Test
    fun foregroundSkipsAfterTheEarlyGates() = runBlocking {
        var ingested = false
        val runner = runner(
            config = config(foreground = true),
            ingest = { ingested = true },
        )
        val outcome = runner.run()
        assertTrue(outcome.skippedForeground)
        assertFalse(ingested)
        assertFalse(outcome.notified)
    }

    @Test
    fun oneNotifyCallBundlesTheChosenItems() = runBlocking {
        val entities = (1..4).map { entity(it.toString(), fresh = true, createdAt = it.toLong()) }
        var calls = 0
        var bundled = 0
        val ledger = FakeLedger(entities)
        val runner = runner(
            config = config(),
            ledger = ledger,
            probe = { items, _ -> items.associate { it.id to ProbeSnapshot("open", false) } },
            notify = { items, _ ->
                calls++
                bundled = items.size
            },
        )
        val outcome = runner.run()
        assertTrue(outcome.notified)
        assertEquals(1, calls)
        assertEquals(3, bundled)
        assertEquals(3, ledger.applied?.toNotify?.size)
    }

    @Test
    fun dailyCapStillAppliesDoneUpdates() = runBlocking {
        val open = entity("n", fresh = true)
        val done = entity("d", fresh = true)
        val ledger = FakeLedger(listOf(open, done))
        var notified = 0
        val runner = runner(
            config = config(sent = 1, cap = 1),
            ledger = ledger,
            probe = { _, _ ->
                mapOf(
                    "n" to ProbeSnapshot("open", false),
                    "d" to ProbeSnapshot("completed", true, "run completed"),
                )
            },
            notify = { _, _ -> notified++ },
        )
        val outcome = runner.run()
        assertEquals(CheckInStop.DAILY_CAP, outcome.result.stop)
        assertEquals(0, notified)
        assertEquals(listOf("d"), ledger.applied?.markDone?.map { it.id })
    }

    private fun runner(
        config: RunnerConfig,
        ledger: OpenItemLedger = FakeLedger(emptyList()),
        ingest: suspend (Long) -> Unit = {},
        probe: suspend (List<OpenItemEntity>, Long) -> Map<String, ProbeSnapshot> = { _, _ -> emptyMap() },
        notify: suspend (List<OpenItemEntity>, String) -> Unit = { _, _ -> },
    ) = CheckInRunner(
        nowMs = { java.time.Instant.parse("2026-06-01T12:00:00Z").toEpochMilli() },
        zone = java.time.ZoneId.of("UTC"),
        readConfig = { config },
        ledger = ledger,
        lastActivityAtMs = { java.time.Instant.parse("2026-06-01T12:00:00Z").toEpochMilli() - 5 * 3_600_000 },
        ingest = ingest,
        probe = probe,
        notify = notify,
        recordNotification = {},
    )

    private fun config(
        paused: Boolean = false,
        foreground: Boolean = false,
        sent: Int = 0,
        cap: Int = 4,
    ) = RunnerConfig(
        enabled = true,
        paused = paused,
        foreground = foreground,
        quietStartMinute = 22 * 60,
        quietEndMinute = 8 * 60,
        inactivityThresholdMs = 2 * 3_600_000,
        dailyCap = cap,
        notificationsSentToday = sent,
    )

    private fun entity(id: String, fresh: Boolean, createdAt: Long = 1) = OpenItemEntity(
        id = id,
        source = "chat",
        sourceId = id,
        kind = "WAITING_ON_USER",
        summary = "item $id",
        probeJson = """{"type":"chat"}""",
        status = "OPEN",
        lastProbeState = "open",
        initialProbeState = "open",
        fingerprint = "fp-$id",
        fresh = fresh,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}

private class FakeLedger(private val rows: List<OpenItemEntity>) : OpenItemLedger {
    var applied: CheckInResult? = null
    override suspend fun listActionable(): List<OpenItemEntity> = rows
    override suspend fun applyResult(result: CheckInResult, probes: Map<String, ProbeSnapshot>, nowMs: Long) {
        applied = result
    }
}
