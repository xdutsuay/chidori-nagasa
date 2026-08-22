package com.druk.lmplayground.coordinator.ui

import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorStatusInfo

/**
 * Pure poll-evaluation helpers for the coordinator monitor (PRD §6.3 / KMA-128).
 * Extracted so disconnect/recovery logic is unit-testable without a ViewModel.
 */
internal sealed interface MonitorPollEvaluation {
    /** Both status and runs endpoints failed — increment toward DISCONNECTED. */
    data class Unreachable(
        val consecutiveFailures: Int,
        val disconnected: Boolean,
        val message: String,
    ) : MonitorPollEvaluation

    /** At least one poll succeeded — reset failure counter and apply fresh data. */
    data class Connected(
        val status: CoordinatorStatusInfo?,
        val runs: List<AgentRunSummary>?,
        val warningMessage: String?,
    ) : MonitorPollEvaluation
}

internal fun evaluateMonitorPoll(
    consecutiveFailures: Int,
    statusResult: Result<CoordinatorStatusInfo>,
    runsResult: Result<List<AgentRunSummary>>,
    disconnectAfter: Int,
): MonitorPollEvaluation {
    val statusUnreachable = statusResult.isFailure
    val runsUnreachable = runsResult.isFailure
    if (statusUnreachable && runsUnreachable) {
        val next = consecutiveFailures + 1
        return MonitorPollEvaluation.Unreachable(
            consecutiveFailures = next,
            disconnected = next >= disconnectAfter,
            message = formatPollErrors(statusResult, runsResult),
        )
    }
    return MonitorPollEvaluation.Connected(
        status = statusResult.getOrNull(),
        runs = runsResult.getOrNull(),
        warningMessage = partialPollWarning(statusResult, runsResult),
    )
}

internal fun formatPollErrors(
    statusResult: Result<CoordinatorStatusInfo>,
    runsResult: Result<List<AgentRunSummary>>,
): String = listOfNotNull(
    statusResult.exceptionOrNull()?.let(::describePollThrowable),
    runsResult.exceptionOrNull()?.let(::describePollThrowable),
).joinToString(" · ").ifBlank { "Can't reach the desktop" }

internal fun partialPollWarning(
    statusResult: Result<CoordinatorStatusInfo>,
    runsResult: Result<List<AgentRunSummary>>,
): String? = when {
    statusResult.isFailure -> statusResult.exceptionOrNull()?.let(::describePollThrowable)
    runsResult.isFailure -> runsResult.exceptionOrNull()?.let(::describePollThrowable)
    else -> null
}

internal fun runningStepsFromRuns(runs: List<AgentRunSummary>): Map<String, String> =
    runs.mapNotNull { run ->
        run.currentStep?.takeIf { it.isNotBlank() }?.let { run.runId to it }
    }.toMap()

internal fun describePollThrowable(t: Throwable): String {
    val name = t.javaClass.simpleName
    return if (t.message != null) "$name: ${t.message}" else name
}
