package com.xdutsuay.chidori.computer.desktop

import com.xdutsuay.chidori.computer.ProotCommand

/**
 * Session geometry and X display for the COS desktop. The default is display `:1`
 * (the number LightYagami's agent contract assumes); tests and the box smoke run
 * override it (e.g. `:42`) so they never collide with a host X server.
 */
data class DesktopConfig(
    val display: Int = 1,
    val width: Int = 1280,
    val height: Int = 720,
    val depth: Int = 24,
    /** Which window-manager session to launch (see DesktopFlavors). Default = the light one. */
    val flavor: DesktopFlavor = DesktopFlavor.OPENBOX,
) {
    init {
        require(display in 0..MAX_DISPLAY) { "display :$display out of range 0..$MAX_DISPLAY" }
        require(width in 64..4096 && height in 64..4096) { "bad size ${width}x$height" }
        require(depth == 16 || depth == 24) { "depth must be 16 or 24, was $depth" }
    }

    val displayName: String get() = ":$display"

    companion object {
        const val MAX_DISPLAY = 999
        val DEFAULT = DesktopConfig()
    }
}

/**
 * Pure builders for the guest side of a desktop session: the Xvfb argv, the session
 * launch script run with `/bin/sh -c` inside proot, and the marker lines it prints.
 *
 * Guest layout (all inside the rootfs, so ComputerPruner never touches it):
 * - `/var/lib/cos-desktop/Xauthority`  – MIT-MAGIC-COOKIE-1 for this session (0600, new per start)
 * - `/var/lib/cos-desktop/fb/Xvfb_screen0` – Xvfb `-fbdir` framebuffer (XWD v7, mmap'd)
 * - `/var/lib/cos-desktop/session.log` – Xvfb / WM / panel / terminal stderr (truncated per start)
 * - `/var/lib/cos-desktop/installed`  – install marker written by [LocalDesktopBridge]
 * - `/tmp/.X11-unix/X<N>`, `/tmp/.X<N>-lock` – the server's only socket and its lock
 */
object DesktopCommand {
    const val GUEST_STATE_DIR = "/var/lib/cos-desktop"
    const val GUEST_XAUTHORITY = "$GUEST_STATE_DIR/Xauthority"
    const val GUEST_FB_DIR = "$GUEST_STATE_DIR/fb"
    const val GUEST_FB_FILE = "$GUEST_FB_DIR/Xvfb_screen0"
    const val GUEST_SESSION_LOG = "$GUEST_STATE_DIR/session.log"
    const val GUEST_INSTALLED_MARKER = "$GUEST_STATE_DIR/installed"

    const val READY_MARKER = "COS-DESKTOP-READY"
    const val ERROR_MARKER = "COS-DESKTOP-ERROR"
    const val STALE_MARKER = "COS-DESKTOP-STALE"
    const val EXIT_MARKER = "COS-DESKTOP-EXIT"

    /** Binaries the session needs inside the guest for the default (Openbox) flavour. */
    val REQUIRED_BINARIES: List<String> = listOf("Xvfb", "xauth", "xdpyinfo", "openbox", "tint2", "xterm")

    /** How long the script waits for `xdpyinfo` to succeed (polls every 0.5 s). */
    const val READY_POLLS = 60

    /**
     * Xvfb argv. Security flags:
     * - `-nolisten tcp`: no TCP port 6000+N.
     * - `-nolisten local`: no abstract-namespace socket (`@/tmp/.X11-unix/XN`); that
     *   namespace is shared by every app on the device, so only the filesystem socket
     *   inside our private rootfs remains.
     * - `-auth <file>`: clients need the per-session MIT-MAGIC-COOKIE-1.
     * - `-fbdir <dir>`: framebuffer as an XWD file we can read for [DesktopBridge.screenshot]
     *   without VNC or an X client.
     */
    fun xvfbArgv(config: DesktopConfig): List<String> = listOf(
        "Xvfb", config.displayName,
        "-screen", "0", "${config.width}x${config.height}x${config.depth}",
        "-nolisten", "tcp",
        "-nolisten", "local",
        "-auth", GUEST_XAUTHORITY,
        "-fbdir", GUEST_FB_DIR,
    )

    /**
     * Guest shell script that runs the whole session in ONE proot process tree:
     * 1. create the state dirs; check the flavour's binaries ([DesktopFlavors.requiredBinaries]);
     * 2. clean a stale lock / socket for display N (a live Xvfb holding it is an error);
     * 3. collision pre-check: if anything already answers on `:N` (e.g. a foreign server on
     *    the shared abstract socket, which our clients would try first), refuse;
     * 4. new 128-bit cookie via `xauth source -` from stdin (never in any argv / `ps`);
     * 5. Xvfb in the background ([xvfbArgv]); wait until `xdpyinfo` succeeds with the cookie;
     * 6. the flavour's session clients ([DesktopFlavors.sessionCommands] — openbox+tint2+xterm,
     *    or XFCE); print [READY_MARKER]; `wait` on Xvfb.
     * The script (and with it proot, via `--kill-on-exit`) lives as long as Xvfb.
     */
    fun launchScript(config: DesktopConfig): String {
        val n = config.display
        val xvfb = xvfbArgv(config).joinToString(" ") { ShellQuote.quote(it) }
        val bins = DesktopFlavors.requiredBinaries(config.flavor).joinToString(" ")
        return """
            |S=$GUEST_STATE_DIR; XA=$GUEST_XAUTHORITY; FB=$GUEST_FB_DIR; LOG=$GUEST_SESSION_LOG; N=$n
            |err() { echo "$ERROR_MARKER ${'$'}*"; exit 1; }
            |umask 022
            |mkdir -p "${'$'}S" "${'$'}FB" /tmp/.X11-unix || err "cannot create ${'$'}S"
            |chmod 1777 /tmp/.X11-unix 2>/dev/null
            |for b in $bins; do command -v "${'$'}b" >/dev/null 2>&1 || err "missing ${'$'}b; install the desktop first"; done
            |if [ -e /tmp/.X${'$'}N-lock ]; then
            |  P=${'$'}(tr -cd 0-9 < /tmp/.X${'$'}N-lock)
            |  if [ -n "${'$'}P" ] && kill -0 "${'$'}P" 2>/dev/null && grep -q Xvfb /proc/${'$'}P/comm 2>/dev/null; then
            |    err "display :${'$'}N already held by running Xvfb pid ${'$'}P"
            |  fi
            |fi
            |if [ -e /tmp/.X${'$'}N-lock ] || [ -e /tmp/.X11-unix/X${'$'}N ]; then
            |  echo "$STALE_MARKER removed stale lock/socket for :${'$'}N"
            |  rm -f /tmp/.X${'$'}N-lock /tmp/.X11-unix/X${'$'}N
            |fi
            |rm -f "${'$'}FB/Xvfb_screen0" "${'$'}XA"
            |: > "${'$'}LOG"
            |PRE=${'$'}(DISPLAY=:${'$'}N XAUTHORITY=/dev/null timeout 5 xdpyinfo 2>&1 >/dev/null); PRC=${'$'}?
            |if [ ${'$'}PRC -eq 0 ] || echo "${'$'}PRE" | grep -qiE 'authoriz|protocol specified'; then
            |  err "display :${'$'}N already served by another X server; pick another display"
            |fi
            |umask 077
            |COOKIE=${'$'}(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
            |[ ${'$'}{#COOKIE} -eq 32 ] || err "cannot generate cookie"
            |echo "add :${'$'}N . ${'$'}COOKIE" | xauth -q -f "${'$'}XA" source - || err "xauth failed"
            |COOKIE=
            |umask 022
            |$xvfb >>"${'$'}LOG" 2>&1 &
            |XPID=${'$'}!
            |export DISPLAY=:${'$'}N XAUTHORITY="${'$'}XA"
            |i=0
            |until xdpyinfo >/dev/null 2>&1; do
            |  kill -0 ${'$'}XPID 2>/dev/null || err "Xvfb exited: ${'$'}(tail -n 3 "${'$'}LOG" | tr '\n' ' ')"
            |  i=${'$'}((i+1)); [ ${'$'}i -ge $READY_POLLS ] && { kill ${'$'}XPID; err "X server not ready after ${READY_POLLS / 2}s"; }
            |  sleep 0.5
            |done
            |${DesktopFlavors.sessionCommands(config.flavor)}
            |echo "$READY_MARKER display=:${'$'}N width=${config.width} height=${config.height} depth=${config.depth}"
            |wait ${'$'}XPID
            |RC=${'$'}?
            |echo "$EXIT_MARKER xvfb rc=${'$'}RC"
            |exit ${'$'}RC
        """.trimMargin()
    }

    /** Guest argv for the session (one proot tree). */
    fun sessionArgv(config: DesktopConfig): List<String> = ProotCommand.guestShellArgv(launchScript(config))

    /** HOME for desktop commands (same as cos_exec's guest HOME). */
    const val GUEST_HOME = "/root"

    /**
     * Guest argv for [DesktopBridge.execInDesktop]: `sh -c` with DISPLAY, XAUTHORITY and
     * HOME exported, then [cmd] verbatim. [cmd] is already shell-quoted by the caller
     * (the chat tools build it with [ShellQuote.join]); it is not re-quoted here.
     */
    fun execArgv(config: DesktopConfig, cmd: String): List<String> = ProotCommand.guestShellArgv(
        "export DISPLAY=${config.displayName} XAUTHORITY=$GUEST_XAUTHORITY HOME=$GUEST_HOME; $cmd",
    )

    data class Ready(val display: String, val width: Int, val height: Int, val depth: Int)

    private val READY_RE = Regex("^$READY_MARKER display=(:\\d+) width=(\\d+) height=(\\d+) depth=(\\d+)\\s*$")

    fun parseReady(line: String): Ready? {
        val m = READY_RE.matchEntire(line.trim()) ?: return null
        return Ready(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
    }

    /** Message of a `COS-DESKTOP-ERROR <msg>` line, else null. */
    fun parseError(line: String): String? {
        val t = line.trim()
        return if (t.startsWith("$ERROR_MARKER ")) t.removePrefix("$ERROR_MARKER ").trim() else null
    }
}
