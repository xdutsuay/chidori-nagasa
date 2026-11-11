package com.xdutsuay.chidori.computer.desktop.input

import com.xdutsuay.chidori.computer.desktop.DesktopBridge
import com.xdutsuay.chidori.computer.desktop.DesktopStream
import com.xdutsuay.chidori.tools.FakeDesktopBridge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted stand-in for `exec xdotool -` / `exec /bin/sh` behind [DesktopBridge.openDesktopStream].
 * Script mode answers `getmouselocation`; shell mode answers `echo "@@cos-sync $?"` with [shellExit].
 */
class FakeDesktopStream(
    val cmd: String,
    private val scriptWorks: Boolean,
    private val shellHasXdotool: Boolean,
    private val onLine: (FakeDesktopStream, String) -> Unit = { _, _ -> },
) : DesktopStream {
    val lines = CopyOnWriteArrayList<String>()
    val closeReasons = CopyOnWriteArrayList<String>()
    private val out = Channel<String>(Channel.UNLIMITED)
    private val err = Channel<String>(Channel.UNLIMITED)
    override val stdout: Flow<String> = out.receiveAsFlow()
    override val stderr: Flow<String> = err.receiveAsFlow()
    override val exitCode = MutableStateFlow<Int?>(null)
    /** When true, markers are not answered (a long `type`, a hung X server). */
    @Volatile var mute = false
    var shellExit = 0
    val isScript get() = cmd.contains("xdotool -")

    init {
        if (isScript && !scriptWorks) die(127)
    }

    override suspend fun write(data: String) {
        if (exitCode.value != null) throw IOException("stream closed")
        for (l in data.split('\n').filter { it.isNotEmpty() }) {
            lines += l
            onLine(this, l)
            if (exitCode.value != null) return
            if (mute) continue
            if (isScript && l == "getmouselocation") out.send("x:10 y:20 screen:0 window:1")
            if (!isScript && l.startsWith("command -v xdotool")) out.send("@@cos-sync " + if (shellHasXdotool) 0 else 1)
            else if (!isScript && l.startsWith("echo \"@@cos-sync")) out.send("@@cos-sync $shellExit")
        }
    }

    fun stderrLine(s: String) { err.trySend(s) }

    fun die(code: Int) {
        if (exitCode.value != null) return
        exitCode.value = code
        out.close(); err.close()
    }

    override suspend fun close(reason: String) {
        closeReasons += reason
        die(130)
    }
}

/** [FakeDesktopBridge] plus stream support. */
class StreamingFakeBridge(
    val base: FakeDesktopBridge = FakeDesktopBridge(),
    var scriptWorks: Boolean = true,
    var shellHasXdotool: Boolean = true,
    var openFails: Boolean = false,
    var onLine: (FakeDesktopStream, String) -> Unit = { _, _ -> },
) : DesktopBridge by base {
    val streams = CopyOnWriteArrayList<FakeDesktopStream>()

    override suspend fun openDesktopStream(cmd: String): DesktopStream {
        if (openFails) throw IllegalStateException("desktop not running")
        return FakeDesktopStream(cmd, scriptWorks, shellHasXdotool, onLine).also { streams += it }
    }

    /** All stream lines with probe/marker bookkeeping removed (the actual xdotool payload). */
    val commandLines: List<String> get() = streams.flatMap { it.lines }.filter { !it.isProbeOrMarker() }
}

/** Probe/marker lines the session writes for its own bookkeeping, in either mode. */
private fun String.isProbeOrMarker() =
    this == "getmouselocation" || startsWith("command -v xdotool") || startsWith("echo \"@@cos-sync")
