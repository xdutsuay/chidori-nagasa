package com.xdutsuay.chidori.computer.desktop

import com.xdutsuay.chidori.computer.ComputerExecResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.time.Duration

/**
 * In-memory [DesktopBridge] that follows [DesktopStateMachine] (install → start → stop),
 * for runtime-side tests, previews and the Computer screen before a device is at hand.
 * (The chat tools keep their own minimal fake in `tools/FakeDesktopBridge` under test.)
 * Every call is recorded in [calls]; script it with [installError], [startError],
 * [execHandler] and [frame].
 */
class ScriptedDesktopBridge(
    initial: DesktopState = DesktopState.Stopped,
    var width: Int = DesktopConfig.DEFAULT.width,
    var height: Int = DesktopConfig.DEFAULT.height,
) : DesktopBridge {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<DesktopState> = _state.asStateFlow()

    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    var installError: String? = null
    var startError: String? = null
    var execHandler: (String) -> ComputerExecResult = { ComputerExecResult(0, "", "") }

    /** Bytes returned by [screenshot] while running (default: 1-byte placeholder). */
    var frame: ByteArray = byteArrayOf(0)

    fun setState(s: DesktopState) {
        _state.value = s
    }

    private fun move(e: DesktopStateMachine.Event): DesktopState {
        DesktopStateMachine.next(_state.value, e)?.let { _state.value = it }
        return _state.value
    }

    fun install(): DesktopState {
        calls += "install"
        if (!DesktopStateMachine.canInstall(_state.value)) return _state.value
        move(DesktopStateMachine.Event.InstallStarted)
        move(DesktopStateMachine.Event.InstallProgress(1f))
        return installError?.let { move(DesktopStateMachine.Event.InstallFailed(it)) }
            ?: move(DesktopStateMachine.Event.InstallSucceeded)
    }

    override suspend fun start(): Result<DesktopState.Running> {
        calls += "start"
        (_state.value as? DesktopState.Running)?.let { return Result.success(it) }
        if (move(DesktopStateMachine.Event.StartRequested) != DesktopState.Starting) {
            return Result.failure(IllegalStateException("cannot start from ${_state.value}"))
        }
        startError?.let {
            move(DesktopStateMachine.Event.StartFailed(it))
            return Result.failure(IllegalStateException(it))
        }
        val running = DesktopState.Running(
            display = DesktopConfig.DEFAULT.displayName,
            width = width,
            height = height,
            depth = DesktopConfig.DEFAULT.depth,
            xauthorityGuestPath = DesktopCommand.GUEST_XAUTHORITY,
            framebufferHostFile = File("/dev/null"),
            vncSocketHostPath = null,
        )
        move(DesktopStateMachine.Event.Ready(running))
        return Result.success(running)
    }

    override suspend fun stop() {
        calls += "stop"
        if (!DesktopStateMachine.canStop(_state.value)) return
        move(DesktopStateMachine.Event.StopRequested)
        move(DesktopStateMachine.Event.Stopped)
    }

    override suspend fun execInDesktop(cmd: String, timeout: Duration): ComputerExecResult {
        calls += "exec:$cmd"
        if (!isDesktopRunning()) return ComputerExecResult(127, "", "desktop not running")
        return execHandler(cmd)
    }

    override suspend fun screenshot(format: ImageFormat, scale: Float): ByteArray {
        calls += "screenshot:$format:$scale"
        check(isDesktopRunning()) { "desktop not running" }
        return frame
    }
}
