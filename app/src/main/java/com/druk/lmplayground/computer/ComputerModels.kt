package com.druk.lmplayground.computer

/**
 * Lifecycle status of the on-device computer (proot/Alpine) runtime.
 */
sealed class ComputerStatus {
    /** computer.enabled flag is off. */
    data object Disabled : ComputerStatus()

    /** Enabled but the COS rootfs has not been provisioned yet. */
    data object MissingRootfs : ComputerStatus()

    /** Enabled, rootfs present, nothing running. */
    data object Ready : ComputerStatus()

    /** A command / session is currently running. */
    data object Running : ComputerStatus()

    /** Terminal failure state with a human-readable message. */
    data class Error(val message: String) : ComputerStatus()
}

/** Result of executing a command inside the computer runtime. */
data class ComputerExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * Contract for the on-device computer runtime. Production implementation
 * (proot-based) is owned by the implementer; tests use [FakeComputerRuntime].
 */
interface ComputerRuntime {
    fun status(): ComputerStatus

    /** Download/verify the COS rootfs if missing; returns resulting status. */
    suspend fun ensureRootfs(): ComputerStatus

    suspend fun exec(argv: List<String>): ComputerExecResult

    fun stop()
}
