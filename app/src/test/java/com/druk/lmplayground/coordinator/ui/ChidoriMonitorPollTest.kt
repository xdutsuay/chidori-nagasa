package com.druk.lmplayground.coordinator.ui

import com.druk.lmplayground.coordinator.model.AgentMode
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.CoordinatorStatusInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChidoriMonitorPollTest {

    @Test
    fun `both endpoints fail increments toward disconnect`() {
        val statusResult = Result.failure<CoordinatorStatusInfo>(IOException("timeout"))
        val runsResult = Result.failure<List<AgentRunSummary>>(IOException("reset"))

        val first = evaluateMonitorPoll(0, statusResult, runsResult, disconnectAfter = 2)
        assertTrue(first is MonitorPollEvaluation.Unreachable)
        first as MonitorPollEvaluation.Unreachable
        assertEquals(1, first.consecutiveFailures)
        assertFalse(first.disconnected)
        assertTrue(first.message.contains("timeout"))
        assertTrue(first.message.contains("reset"))

        val second = evaluateMonitorPoll(1, statusResult, runsResult, disconnectAfter = 2)
        assertTrue(second is MonitorPollEvaluation.Unreachable)
        second as MonitorPollEvaluation.Unreachable
        assertEquals(2, second.consecutiveFailures)
        assertTrue(second.disconnected)
    }

    @Test
    fun `one endpoint succeeding resets and applies data`() {
        val status = CoordinatorStatusInfo(CoordinatorStatus.RUNNING, null)

        val evaluation = evaluateMonitorPoll(
            consecutiveFailures = 3,
            statusResult = Result.success(status),
            runsResult = Result.failure(IOException("runs down")),
            disconnectAfter = 2,
        )

        assertTrue(evaluation is MonitorPollEvaluation.Connected)
        evaluation as MonitorPollEvaluation.Connected
        assertEquals(status, evaluation.status)
        assertEquals(null, evaluation.runs)
        assertTrue(evaluation.warningMessage!!.contains("runs down"))
    }

    @Test
    fun `runningStepsFromRuns picks non-blank current steps`() {
        val runs = listOf(
            AgentRunSummary("a", AgentMode.ASK, 0L, AgentRunState.RUNNING, "Step A"),
            AgentRunSummary("b", AgentMode.PLAN, 0L, AgentRunState.COMPLETED, ""),
            AgentRunSummary("c", AgentMode.DEBUG, 0L, AgentRunState.RUNNING, null),
        )

        assertEquals(mapOf("a" to "Step A"), runningStepsFromRuns(runs))
    }

    @Test
    fun `formatPollErrors joins status and runs failures`() {
        val msg = formatPollErrors(
            Result.failure(IOException("timeout")),
            Result.failure(IOException("reset")),
        )
        assertEquals("IOException: timeout · IOException: reset", msg)
    }
}
