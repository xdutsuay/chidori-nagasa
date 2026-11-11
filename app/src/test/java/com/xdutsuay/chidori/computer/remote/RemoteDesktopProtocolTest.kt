package com.xdutsuay.chidori.computer.remote

import com.xdutsuay.chidori.computer.desktop.ShellQuote
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [RemoteDesktopProtocol], the CLI + output grammar of the
 * `cindi-desktop` helper script that this app drives over SSH on the user's VM.
 */
class RemoteDesktopProtocolTest {

    // ------------------------------------------------------------------ constants

    @Test
    fun `helper name is cindi-desktop`() {
        assertEquals("cindi-desktop", RemoteDesktopProtocol.HELPER)
    }

    @Test
    fun `transport failure exit code is 255`() {
        assertEquals(255, RemoteDesktopProtocol.EXIT_TRANSPORT)
    }

    // ------------------------------------------------------------------ commands

    @Test
    fun `startCommand runs helper start`() {
        assertEquals("cindi-desktop start", RemoteDesktopProtocol.startCommand())
    }

    @Test
    fun `stopCommand runs helper stop`() {
        assertEquals("cindi-desktop stop", RemoteDesktopProtocol.stopCommand())
    }

    @Test
    fun `shotCommand runs helper shot`() {
        assertEquals("cindi-desktop shot", RemoteDesktopProtocol.shotCommand())
    }

    @Test
    fun `execCommand shell-quotes the command after the exec subcommand`() {
        val cmd = "echo 'a b' ${'$'}VAR"
        val expected = "cindi-desktop exec " + ShellQuote.quote(cmd)

        assertEquals(expected, RemoteDesktopProtocol.execCommand(cmd))
        // Literal expected form: single quotes doubled as '\'' so the remote
        // shell sees the raw command with no expansion of $VAR.
        assertEquals("cindi-desktop exec 'echo '\\''a b'\\'' ${'$'}VAR'", RemoteDesktopProtocol.execCommand(cmd))
    }

    @Test
    fun `execCommand quotes a plain command too`() {
        assertEquals("cindi-desktop exec 'ls -la'", RemoteDesktopProtocol.execCommand("ls -la"))
    }

    // ------------------------------------------------------------------ parseStart

    @Test
    fun `parseStart picks the OK line among log lines`() {
        assertEquals(
            RemoteDesktopProtocol.Display(":9", 1280, 720, 24),
            RemoteDesktopProtocol.parseStart("starting...\nOK :9 1280x720x24\n"),
        )
    }

    @Test
    fun `parseStart tolerates CRLF line endings`() {
        assertEquals(
            RemoteDesktopProtocol.Display(":1", 800, 600, 16),
            RemoteDesktopProtocol.parseStart("booting Xvfb\r\nOK :1 800x600x16\r\n"),
        )
    }

    @Test
    fun `parseStart returns the first matching OK line`() {
        assertEquals(
            RemoteDesktopProtocol.Display(":0", 640, 480, 8),
            RemoteDesktopProtocol.parseStart("noise\nOK :0 640x480x8\nOK :1 100x100x8\n"),
        )
    }

    @Test
    fun `parseStart rejects display without leading colon`() {
        assertNull(RemoteDesktopProtocol.parseStart("OK 9 1280x720x24"))
    }

    @Test
    fun `parseStart rejects missing depth field`() {
        assertNull(RemoteDesktopProtocol.parseStart("OK :9 1280x720"))
    }

    @Test
    fun `parseStart rejects non-positive dimensions`() {
        assertNull(RemoteDesktopProtocol.parseStart("OK :9 0x720x24"))
        assertNull(RemoteDesktopProtocol.parseStart("OK :9 1280x-1x24"))
    }

    @Test
    fun `parseStart rejects error output`() {
        assertNull(RemoteDesktopProtocol.parseStart("ERR no Xvfb"))
    }

    @Test
    fun `parseStart rejects empty output`() {
        assertNull(RemoteDesktopProtocol.parseStart(""))
    }

    // ------------------------------------------------------------------ decodeShot

    private fun fakePng(extra: ByteArray = byteArrayOf(1, 2, 3, 4, 5)): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + extra

    @Test
    fun `decodeShot accepts base64 split across lines`() {
        val bytes = fakePng()
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val multiline = b64.chunked(24).joinToString("\n")

        assertTrue("decoded bytes must equal the original PNG", RemoteDesktopProtocol.decodeShot(multiline)!!.contentEquals(bytes))

    }

    @Test
    fun `decodeShot strips all whitespace including spaces inside the payload`() {
        val bytes = fakePng(byteArrayOf(9, 8, 7))
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val sprinkled = b64.chunked(7).joinToString(" \r\n ")

        assertTrue(RemoteDesktopProtocol.decodeShot(sprinkled)!!.contentEquals(bytes))
    }

    @Test
    fun `decodeShot rejects bytes without the PNG magic`() {
        val notPng = "just some text".toByteArray(Charsets.UTF_8)

        assertNull(RemoteDesktopProtocol.decodeShot(Base64.getEncoder().encodeToString(notPng)))
    }

    @Test
    fun `decodeShot rejects invalid base64`() {
        assertNull(RemoteDesktopProtocol.decodeShot("!!!not base64!!!"))
    }

    @Test
    fun `decodeShot rejects empty payload`() {
        assertNull(RemoteDesktopProtocol.decodeShot(""))
        assertNull(RemoteDesktopProtocol.decodeShot("  \n  "))
    }

    @Test
    fun `decodeShot keeps the full payload beyond the magic`() {
        val bytes = fakePng(byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()))

        assertTrue(RemoteDesktopProtocol.decodeShot(Base64.getEncoder().encodeToString(bytes))!!.contentEquals(bytes))
    }

    // ------------------------------------------------------------------ toExecResult

    @Test
    fun `toExecResult maps Ok verbatim`() {
        val result = RemoteDesktopProtocol.toExecResult(SshResult.Ok(0, "stdout data", "stderr data"))

        assertEquals(0, result.exitCode)
        assertEquals("stdout data", result.stdout)
        assertEquals("stderr data", result.stderr)
    }

    @Test
    fun `toExecResult maps UnknownHostKey to transport failure mentioning not trusted`() {
        val result = RemoteDesktopProtocol.toExecResult(SshResult.UnknownHostKey("SHA256:abc123"))

        assertEquals(RemoteDesktopProtocol.EXIT_TRANSPORT, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("SHA256:abc123"))
        assertTrue(result.stderr.contains("not trusted"))
    }

    @Test
    fun `toExecResult maps HostKeyChanged to transport failure mentioning both keys`() {
        val result =
            RemoteDesktopProtocol.toExecResult(SshResult.HostKeyChanged("SHA256:pinned", "SHA256:presented"))

        assertEquals(RemoteDesktopProtocol.EXIT_TRANSPORT, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("SHA256:pinned"))
        assertTrue(result.stderr.contains("SHA256:presented"))
        assertTrue(result.stderr.contains("changed"))
    }

    @Test
    fun `toExecResult maps Failed to transport failure with the message`() {
        val result = RemoteDesktopProtocol.toExecResult(SshResult.Failed("connection refused"))

        assertEquals(RemoteDesktopProtocol.EXIT_TRANSPORT, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("connection refused"))
    }

    @Test
    fun `toExecResult never leaks transport failure as a zero exit code`() {
        for (r in listOf(
            SshResult.UnknownHostKey("SHA256:x"),
            SshResult.HostKeyChanged("SHA256:a", "SHA256:b"),
            SshResult.Failed("m"),
        )) {
            val mapped = RemoteDesktopProtocol.toExecResult(r)
            assertNotNull(mapped)
            assertFalse("transport failures must not report success", mapped.exitCode == 0)
        }
    }
}
