package com.xdutsuay.chidori.computer

/**
 * Fully controllable in-memory [ComputerRuntime] for tests.
 * Honors the enabled gate so tests can exercise the Disabled short-circuit.
 *
 * Streaming: intentionally **non-streaming**. [ComputerRuntime] only exposes
 * [exec]; production line streaming lives on [LocalComputerRuntime.execStreaming]
 * and is exercised via the real runtime / UI. Do not add a streaming helper
 * here unless the interface gains an `execStreaming` contract.
 */
class FakeComputerRuntime(
    /** Whether the computer feature flag is on (defaults to on). */
    var enabled: Boolean = true,
    /** Status reported while enabled. */
    var status: ComputerStatus = ComputerStatus.Ready,
    /** Status returned by [ensureRootfs]. */
    var rootfsStatusAfterEnsure: ComputerStatus = ComputerStatus.Ready,
    /** Result returned by [exec] when enabled. */
    var execResult: ComputerExecResult = ComputerExecResult(exitCode = 0, stdout = "", stderr = ""),
) : ComputerRuntime {

    val execCalls = mutableListOf<List<String>>()
    var ensureRootfsCalls = 0
    var stopCalls = 0

    override fun status(): ComputerStatus =
        if (!enabled) ComputerStatus.Disabled else status

    override suspend fun ensureRootfs(): ComputerStatus {
        ensureRootfsCalls++
        return rootfsStatusAfterEnsure
    }

    override suspend fun exec(argv: List<String>): ComputerExecResult {
        if (!enabled) {
            // Disabled short-circuit: nothing recorded, non-zero exit.
            return ComputerExecResult(
                exitCode = 127,
                stdout = "",
                stderr = "computer disabled",
            )
        }
        execCalls += argv
        return execResult
    }

    override fun stop() {
        stopCalls++
    }
}
