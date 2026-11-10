package com.druk.lmplayground.computer.remote

import com.druk.lmplayground.computer.ComputerExecResult
import com.druk.lmplayground.computer.desktop.ShellQuote
import java.util.Base64

/** CLI and output grammar of the `cindi-desktop` helper (scripts/cindi-vm) driven over SSH. */
object RemoteDesktopProtocol {
    const val HELPER = "cindi-desktop"
    const val EXIT_TRANSPORT = 255

    private val OK_LINE = Regex("""^OK (:\d+) (\d+)x(\d+)x(\d+)$""")
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val WHITESPACE = Regex("""\s+""")

    data class Display(val display: String, val width: Int, val height: Int, val depth: Int)

    fun startCommand(): String = "$HELPER start"

    fun stopCommand(): String = "$HELPER stop"

    fun shotCommand(): String = "$HELPER shot"

    fun execCommand(cmd: String): String = "$HELPER exec " + ShellQuote.quote(cmd)

    fun parseStart(stdout: String): Display? {
        for (line in stdout.lineSequence()) {
            val m = OK_LINE.matchEntire(line.trimEnd('\r')) ?: continue
            val (display, w, h, d) = m.destructured
            val width = w.toIntOrNull() ?: continue
            val height = h.toIntOrNull() ?: continue
            val depth = d.toIntOrNull() ?: continue
            if (width <= 0 || height <= 0 || depth <= 0) continue
            return Display(display, width, height, depth)
        }
        return null
    }

    fun decodeShot(stdout: String): ByteArray? {
        val clean = stdout.replace(WHITESPACE, "")
        if (clean.isEmpty()) return null
        val bytes = try {
            Base64.getDecoder().decode(clean)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size < PNG_MAGIC.size) return null
        for (i in PNG_MAGIC.indices) if (bytes[i] != PNG_MAGIC[i]) return null
        return bytes
    }

    fun toExecResult(result: SshResult): ComputerExecResult = when (result) {
        is SshResult.Ok -> ComputerExecResult(result.exitCode, result.stdout, result.stderr)
        is SshResult.UnknownHostKey ->
            ComputerExecResult(EXIT_TRANSPORT, "", "host key ${result.fingerprint} is not trusted yet")
        is SshResult.HostKeyChanged ->
            ComputerExecResult(
                EXIT_TRANSPORT,
                "",
                "host key changed: pinned ${result.pinned}, server presented ${result.presented}",
            )
        is SshResult.Failed -> ComputerExecResult(EXIT_TRANSPORT, "", result.message)
    }
}
