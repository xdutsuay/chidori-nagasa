package com.druk.lmplayground.checkin

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.druk.lmplayground.App
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.team.TeamRoomRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Periodic check-in. Separate from download workers. Battery-not-low is a
 * WorkManager constraint; foreground, pause, quiet hours, and inactivity
 * are enforced by [CheckInRunner] so they can be unit tested.
 *
 * Failures return success. A retry would risk a second nudge for the same
 * news, and the next periodic run will try again.
 */
class CheckInWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        return try {
            CheckInRuntime.runner(app).run()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check-in run failed", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "CheckInWorker"
    }
}

/**
 * Wires [CheckInRunner] to the process singletons on [App]. Desktop listRuns
 * happens once per paired instance and is shared by ingest and the probe.
 */
internal object CheckInRuntime {
    fun runner(app: App, zone: ZoneId = ZoneId.systemDefault()): CheckInRunner {
        val prefs = CheckInPreferences(app)
        val pause = BackgroundActivityPause(app)
        val cache = DesktopProbeCache()
        val capture = app.openItemCapture
        return CheckInRunner(
            nowMs = { System.currentTimeMillis() },
            zone = zone,
            readConfig = {
                val now = System.currentTimeMillis()
                RunnerConfig(
                    enabled = prefs.enabled,
                    paused = pause.isPaused,
                    foreground = app.isAppInForeground,
                    quietStartMinute = prefs.quietStartMinute,
                    quietEndMinute = prefs.quietEndMinute,
                    inactivityThresholdMs = prefs.inactivityThresholdMs,
                    dailyCap = prefs.frequency.dailyCap(),
                    notificationsSentToday = prefs.notificationsSent(CheckInGates.dayKey(now, zone)),
                )
            },
            ledger = app.openItemRepository,
            lastActivityAtMs = {
                val message = app.chatRepository.latestMessageTimestamp()
                val usage = app.usageRepository.latestCreatedAt()
                listOfNotNull(message, usage).maxOrNull()
            },
            ingest = { now ->
                refreshDesktop(app, cache)
                DesktopRunIngest.ingest(cache, app.openItemRepository, now)
                TeamRoomRegistry.current?.let { room ->
                    TeamHandoffIngest.syncRoom(room, app.openItemRepository, now)
                }
                capture?.drainPending()
            },
            probe = { items, now ->
                CheckInProber.probe(
                    items = items,
                    nowMs = now,
                    runsByInstance = cache.runs,
                    failedInstanceIds = cache.failedInstanceIds,
                    room = TeamRoomRegistry.current,
                )
            },
            notify = { items, text ->
                CheckInNotifier.post(app, items, text)
            },
            recordNotification = { day -> prefs.recordNotification(day) },
            wording = { items ->
                val drafted = runCatching { app.checkInCompleter?.complete(CheckInCopy.wordingPrompt(items)) }
                    .getOrNull()
                CheckInCopy.notificationText(drafted, items)
            },
        )
    }

    private suspend fun refreshDesktop(app: App, cache: DesktopProbeCache) {
        val paired = runCatching { app.coordinatorRepository.observePairedInstances().first() }
            .getOrElse {
                cache.runs = emptyMap()
                cache.failedInstanceIds = emptySet()
                return
            }
        val runs = LinkedHashMap<String, List<AgentRunSummary>>()
        val failed = mutableSetOf<String>()
        for (instance in paired) {
            val id = instance.instanceId.value
            val listed = runCatching { app.coordinatorRepository.listRuns(instance.instanceId) }.getOrNull()
            if (listed == null) failed += id else runs[id] = listed
        }
        cache.runs = runs
        cache.failedInstanceIds = failed
    }
}
