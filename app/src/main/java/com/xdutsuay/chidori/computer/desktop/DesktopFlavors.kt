package com.xdutsuay.chidori.computer.desktop

/**
 * Pure (no Android imports) decision logic for the COS desktop extras: the desktop
 * flavour (Openbox vs XFCE), the optional Firefox add-on, the Openbox background
 * colour and the guest apk repositories. Deterministic so it is JVM-unit-testable.
 *
 * The Android glue (SharedPreferences, dialogs) lives in [DesktopExtrasPrefs] /
 * DesktopExtrasDialog.kt; the apk wiring lives in [LocalDesktopBridge]; the session
 * script consumes [DesktopFlavors.sessionCommands] via [DesktopCommand.launchScript].
 */

/** Which window-manager session the COS desktop runs after install. */
enum class DesktopFlavor(
    /** Stable id persisted in SharedPreferences ([DesktopFlavors.PREFS_KEY_DESKTOP_FLAVOR]). */
    val id: String,
    /** Short human-readable name for pure-code strings (dialogs use resources). */
    val label: String,
) {
    OPENBOX("openbox", "Openbox (light)"),
    XFCE("xfce", "XFCE"),
    ;

    companion object {
        /** Unknown / missing persisted ids fall back to the light default. */
        fun fromId(id: String?): DesktopFlavor = entries.firstOrNull { it.id == id } ?: OPENBOX
    }
}

object DesktopFlavors {

    /** SharedPreferences keys (stored in [com.xdutsuay.chidori.computer.CosConstants.PREFS_NAME]). */
    const val PREFS_KEY_DESKTOP_FLAVOR = "desktop_flavor"
    const val PREFS_KEY_FIREFOX = "desktop_firefox"

    /**
     * Dark slate background for the light Openbox session — without it the framebuffer
     * is pure black, which reads as "broken" on the screenshot / viewer.
     */
    const val OPENBOX_BACKGROUND_COLOR = "#1B1E28"

    /**
     * Tiny package that paints the Openbox background. Installed unpinned (its closure
     * is libX11, already present in the default set): 7.5–7.7 KiB download per arch,
     * well below the 0.1 MiB rounding of the shown sizes.
     */
    const val WALLPAPER_PACKAGE = "xsetroot"

    /**
     * XFCE top-level packages; apk pulls the rest of the session (xfwm4, xfdesktop,
     * xfce4-panel, xfconf, dbus-x11, thunar, …) as dependencies of the `xfce4` meta
     * package. Alpine v3.24 versions at snapshot time: xfce4=4.20-r0,
     * xfce4-terminal=1.2.0-r0 (see [DesktopPackages.SET_XFCE] for sizes).
     */
    val XFCE_PACKAGES: List<String> = listOf("xfce4", "xfce4-terminal")

    /** Package names the user is shown for a flavour + Firefox choice (dialog labels). */
    fun packagesFor(flavor: DesktopFlavor, firefox: Boolean): List<String> =
        DesktopPackages.DEFAULT_PACKAGES +
            (if (flavor == DesktopFlavor.XFCE) XFCE_PACKAGES else emptyList()) +
            (if (firefox) DesktopPackages.FIREFOX_PACKAGES else emptyList())

    /** Binaries the session script refuses to start without, per flavour. */
    fun requiredBinaries(flavor: DesktopFlavor): List<String> = when (flavor) {
        DesktopFlavor.OPENBOX -> DesktopCommand.REQUIRED_BINARIES
        DesktopFlavor.XFCE ->
            listOf("Xvfb", "xauth", "xdpyinfo", "xwd", "xfwm4", "xfdesktop", "xfce4-panel", "xfce4-terminal")
    }

    /**
     * Client-launch section of the session script (runs after Xvfb is ready, with
     * DISPLAY/XAUTHORITY exported and `$LOG` defined). Pure and deterministic:
     * the default (Openbox) output must keep the exact lines the existing
     * DesktopCommandTest order assertions pin (`openbox >>`, `tint2 >>`, `xterm -geometry`).
     */
    fun sessionCommands(flavor: DesktopFlavor): String = when (flavor) {
        DesktopFlavor.OPENBOX -> """
            |command -v xsetroot >/dev/null 2>&1 && xsetroot -solid $OPENBOX_BACKGROUND_COLOR >>"${'$'}LOG" 2>&1
            |openbox >>"${'$'}LOG" 2>&1 &
            |tint2 >>"${'$'}LOG" 2>&1 &
            |xterm -geometry 100x28+24+24 >>"${'$'}LOG" 2>&1 &
        """.trimMargin()

        DesktopFlavor.XFCE ->
            // xfce4-session would need a session manager handshake; the WM + desktop +
            // panel + terminal over a session dbus is the minimal working XFCE desktop.
            // xfdesktop paints the background, so no xsetroot needed here.
            """
            |if command -v dbus-launch >/dev/null 2>&1; then
            |  eval "${'$'}(dbus-launch --sh-syntax 2>>"${'$'}LOG")" || true
            |fi
            |xfwm4 >>"${'$'}LOG" 2>&1 &
            |xfdesktop >>"${'$'}LOG" 2>&1 &
            |xfce4-panel >>"${'$'}LOG" 2>&1 &
            |xfce4-terminal --geometry 100x28+24+24 >>"${'$'}LOG" 2>&1 &
        """.trimMargin()
    }

    /** `"3.24.3"` -> `"3.24"` (first two components; already-short versions pass through). */
    fun alpineSeries(alpineVersion: String): String {
        val first = alpineVersion.indexOf('.')
        if (first < 0) return alpineVersion
        val second = alpineVersion.indexOf('.', first + 1)
        return if (second < 0) alpineVersion else alpineVersion.substring(0, second)
    }

    /**
     * Full `/etc/apk/repositories` content for the pinned Alpine version: main and
     * community from [mirrorBase] (community is required for firefox / xfce4).
     */
    fun repositoriesFile(alpineVersion: String, mirrorBase: String): String {
        val base = mirrorBase.trimEnd('/')
        val series = alpineSeries(alpineVersion)
        return "$base/v$series/main\n$base/v$series/community\n"
    }

    /**
     * Idempotent guest shell command that guarantees the community repo of the pinned
     * Alpine version is enabled in `/etc/apk/repositories` (the shipped minirootfs has
     * main + community already; this guards reinstalls / edited rootfs images).
     */
    fun ensureRepositoriesCommand(alpineVersion: String, mirrorBase: String): String {
        val series = alpineSeries(alpineVersion)
        val community = "${mirrorBase.trimEnd('/')}/v$series/community"
        return "f=/etc/apk/repositories; grep -q \"/v$series/community\" \"\$f\" || echo \"$community\" >> \"\$f\""
    }
}

/** Guest-side Firefox launcher for the agent (`cos-firefox` via execInDesktop / cos_exec). */
object FirefoxLauncher {

    /** Wrapper script installed inside the guest. */
    const val GUEST_WRAPPER_PATH = "/usr/local/bin/cos-firefox"

    /**
     * Firefox sandboxes use user namespaces / clone3, which proot cannot provide;
     * these env vars are the documented way to run firefox-esr in such a rootfs.
     */
    const val ENV_FAKE_NO_SANDBOX = "MOZ_FAKE_NO_SANDBOX"
    const val ENV_DISABLE_CONTENT_SANDBOX = "MOZ_DISABLE_CONTENT_SANDBOX"
    const val ENV_DISABLE_GMP_SANDBOX = "MOZ_DISABLE_GMP_SANDBOX"

    /** The wrapper script contents (no Android deps; deterministic for tests). */
    fun wrapperScript(): String = """
        |#!/bin/sh
        |# proot cannot provide the user namespaces Firefox's sandboxes need; the env
        |# below is how firefox-esr runs inside such a rootfs.
        |export $ENV_FAKE_NO_SANDBOX=1
        |export $ENV_DISABLE_CONTENT_SANDBOX=1
        |export $ENV_DISABLE_GMP_SANDBOX=1
        |if command -v firefox-esr >/dev/null 2>&1; then
        |  exec firefox-esr "${'$'}@"
        |fi
        |exec firefox "${'$'}@"
    """.trimMargin()

    /** Guest command that installs the wrapper (runs as root inside proot; idempotent). */
    fun installCommand(): String =
        "mkdir -p /usr/local/bin && printf '%s\\n' " + ShellQuote.quote(wrapperScript()) +
            " > $GUEST_WRAPPER_PATH && chmod 0755 $GUEST_WRAPPER_PATH"
}
