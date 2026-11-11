package com.xdutsuay.chidori.computer.desktop

import com.xdutsuay.chidori.computer.CosConstants
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Pinned Alpine [CosConstants.ALPINE_SERIES] package sets for the COS desktop (CP2).
 *
 * The pins are the top-level packages installed with `apk add name=version`; apk then
 * resolves their dependency closure from the same signed index (apk verifies the index
 * and every package signature against `/etc/apk/keys`). Sizes are the closure of the
 * set minus the 16 packages of the pinned minirootfs, computed from the APKINDEX
 * snapshots whose sha256 is recorded in [APKINDEX_SHA256]
 * (`research/artifacts/cp2/resolve.py`, M1 run on 2026-11-04).
 *
 * Bumping a pin: re-fetch both indexes per arch, recompute the closure sizes and update
 * the pins, sizes and index hashes together. Alpine keeps only the newest build of a
 * package in a stable branch, so a pin stops resolving when Alpine ships a security
 * rebuild; the installer then fails clearly (see [failureHint]).
 */
object DesktopPackages {

    /** One pinned install set for one Alpine arch. */
    data class PinnedSet(
        val id: String,
        val arch: String,
        /** Top-level package -> exact `version-rN` passed to `apk add name=version`. */
        val pins: Map<String, String>,
        /** Packages apk installs for this set on a fresh minirootfs (closure minus base). */
        val closurePackages: Int,
        /** Sum of `.apk` sizes (APKINDEX `S:`) = what is downloaded. */
        val downloadBytes: Long,
        /** Sum of installed sizes (APKINDEX `I:`) = what lands in the rootfs. */
        val installedBytes: Long,
    ) {
        /** `name=version` arguments for `apk add`, in a stable order. */
        val apkAddArgs: List<String> get() = pins.map { (n, v) -> "$n=$v" }
    }

    const val SET_DEFAULT = "desktop-default"
    const val SET_FIREFOX = "desktop-firefox-esr"
    const val SET_XFCE = "desktop-xfce"

    /**
     * Default desktop set: minimal X stack (no VNC in v1) + a light browser and file manager.
     * `xdpyinfo` (readiness probe), `xauth` (MIT-MAGIC-COOKIE) and `xwd` are required by
     * [DesktopSession]; `xdotool` is for the agent (M2).
     */
    val DEFAULT_PACKAGES: List<String> = listOf(
        "xvfb", "openbox", "tint2", "xterm", "xdotool", "xdpyinfo", "xwd", "xauth",
        "font-dejavu", "netsurf", "pcmanfm",
    )

    /** Opt-in on top of the default set (heavy: +~110 MiB download, ~275–315 MiB installed). */
    val FIREFOX_PACKAGES: List<String> = listOf("firefox-esr")

    /** Identical on aarch64 and x86_64 in the recorded snapshot. */
    private val DEFAULT_PINS: Map<String, String> = linkedMapOf(
        "xvfb" to "21.1.25-r0",
        "openbox" to "3.6.1-r8",
        "tint2" to "17.1.3-r1",
        "xterm" to "410-r0",
        "xdotool" to "4.20260303.1-r0",
        "xdpyinfo" to "1.4.0-r0",
        "xwd" to "1.0.9-r2",
        "xauth" to "1.1.5-r0",
        "font-dejavu" to "2.37-r6",
        "netsurf" to "3.11-r1",
        "pcmanfm" to "1.3.2-r2",
    )

    private val FIREFOX_PINS: Map<String, String> = linkedMapOf("firefox-esr" to "140.12.0-r0")

    /**
     * XFCE flavour increment on top of the default set (the shared X stack — Xvfb,
     * xauth, xdpyinfo, xwd, xdotool — comes from [DEFAULT_PINS]; apk adds
     * xfwm4/xfdesktop/xfce4-panel/xfconf/dbus-x11/thunar/… as `xfce4` dependencies).
     * Pins + sizes from APKINDEX snapshots fetched 2026-11-10 (dl-cdn.alpinelinux.org,
     * sha256 aarch64 main 5bdf9ba1801089651c6f83a21cd7c8ef495d6d11cffb61b5827ee87a6d6c65ab,
     * aarch64 community ac06e17cb995ffeaea60bd966d0f011faf8e6688c3c3b62dccbfc8ff7392f7da,
     * x86_64 main 1c393e745f0c3af8b94d6a6125373d00cc88b401ad468d28a5d0dc7f7ee7c829,
     * x86_64 community 2f7e6294f27beb60fc4731c62f31ca4d49c334cb20ca7dba3f0987bb0e7e92a8);
     * closure resolved like the M1 run (closure minus the 16 minirootfs base packages
     * minus the default-set overlap; the resolver reproduced the recorded default-set
     * installed bytes exactly on aarch64, within ~20 KiB elsewhere).
     */
    private val XFCE_PINS: Map<String, String> = linkedMapOf(
        "xfce4" to "4.20-r0",
        "xfce4-terminal" to "1.2.0-r0",
    )

    /** Installed on demand when the developer VNC viewer is first started (same for both arches). */
    val VIEWER_PINS: Map<String, String> = mapOf("x11vnc" to "0.9.17-r1")

    private val SETS: Map<Pair<String, String>, PinnedSet> = listOf(
        PinnedSet(SET_DEFAULT, "aarch64", DEFAULT_PINS, 140, 118_354_286L, 324_693_859L),
        PinnedSet(SET_DEFAULT, "x86_64", DEFAULT_PINS, 140, 123_988_068L, 337_580_815L),
        // Firefox numbers are the increment on top of the default set.
        PinnedSet(SET_FIREFOX, "aarch64", FIREFOX_PINS, 32, 115_763_494L, 287_854_520L),
        PinnedSet(SET_FIREFOX, "x86_64", FIREFOX_PINS, 33, 123_468_692L, 330_394_900L),
        // XFCE numbers are the increment on top of the default set.
        PinnedSet(SET_XFCE, "aarch64", XFCE_PINS, 197, 127_474_247L, 334_912_254L),
        PinnedSet(SET_XFCE, "x86_64", XFCE_PINS, 199, 137_856_850L, 354_719_582L),
    ).associateBy { it.id to it.arch }

    /**
     * sha256 of the `APKINDEX.tar.gz` snapshots the pins and sizes were computed from
     * (`$ALPINE_CDN_BASE/v3.24/<repo>/<arch>/APKINDEX.tar.gz`). Informational: the live
     * index moves on, so a difference is logged, not fatal; the version check after
     * install is what fails hard.
     */
    val APKINDEX_SHA256: Map<String, Map<String, String>> = mapOf(
        "aarch64" to mapOf(
            "main" to "d381e5c94d9f900c9861b454c50f6a9c7f780abdeba1314a192d34dc1304e6a1",
            "community" to "09921dd91ebfbe94be67d79bf9bc8f5e42b46c92f82291bc6aa8044f39ed67e5",
        ),
        "x86_64" to mapOf(
            "main" to "01b3de7a958e1608585fa38fb92fdd98d6c03a59a9709c76927b52e05122450c",
            "community" to "d739291ba2f9cf1a709cea53eb1f0af6603377cb827acfc365a4ce4ebecb6c97",
        ),
    )

    val ARCHES: List<String> = listOf("aarch64", "x86_64")

    fun set(id: String, arch: String): PinnedSet? = SETS[id to arch]

    fun defaultSet(arch: String): PinnedSet? = set(SET_DEFAULT, arch)

    fun firefoxSet(arch: String): PinnedSet? = set(SET_FIREFOX, arch)

    /** XFCE increment on top of the default set, for the flavour choice. */
    fun xfceSet(arch: String): PinnedSet? = set(SET_XFCE, arch)

    /** Sets to install for a request, default first. Empty for an unsupported arch. */
    fun setsFor(arch: String, withFirefox: Boolean): List<PinnedSet> {
        val default = defaultSet(arch) ?: return emptyList()
        return if (withFirefox) listOfNotNull(default, firefoxSet(arch)) else listOf(default)
    }

    /** [setsFor] plus the XFCE increment when [flavor] is [DesktopFlavor.XFCE]. */
    fun setsFor(arch: String, withFirefox: Boolean, flavor: DesktopFlavor): List<PinnedSet> {
        val base = setsFor(arch, withFirefox)
        if (flavor != DesktopFlavor.XFCE) return base
        if (base.isEmpty()) return emptyList()
        return base + listOfNotNull(xfceSet(arch))
    }

    fun downloadBytes(sets: List<PinnedSet>): Long = sets.sumOf { it.downloadBytes }

    fun installedBytes(sets: List<PinnedSet>): Long = sets.sumOf { it.installedBytes }

    /** `112.9 MiB` (one decimal, binary units). */
    fun formatMiB(bytes: Long): String =
        String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))

    /** `~113 MiB` for menu labels / dialogs (rounded to the nearest MiB). */
    fun approxMiB(bytes: Long): String =
        "~" + Math.round(bytes / (1024.0 * 1024.0)) + " MiB"

    /** Result of comparing `apk info -v` output with the pins. */
    data class Verification(
        val missing: List<String>,
        /** name -> installed version, for pins whose installed version differs. */
        val mismatched: Map<String, String>,
    ) {
        val ok: Boolean get() = missing.isEmpty() && mismatched.isEmpty()

        fun describe(pins: Map<String, String>): String = buildList {
            mismatched.forEach { (n, got) -> add("$n: expected ${pins[n]}, installed $got") }
            missing.forEach { n -> add("$n: not installed (expected ${pins[n]})") }
        }.joinToString("; ")
    }

    /**
     * Compares installed packages (lines of `apk info -v`, i.e. `name-version-rN`) with
     * [pins]. A package name may itself contain `-digits` (e.g. `font-dejavu`), so a line
     * matches a pin when it is `name-` followed by a version that starts with a digit
     * and contains no further `-` except the `-rN` release suffix.
     */
    fun verify(installedLines: List<String>, pins: Map<String, String>): Verification {
        val installed = HashMap<String, String>()
        val versionRe = Regex("^(.+)-(\\d[^-]*-r\\d+)$")
        for (raw in installedLines) {
            val line = raw.trim()
            val m = versionRe.matchEntire(line) ?: continue
            installed[m.groupValues[1]] = m.groupValues[2]
        }
        val missing = mutableListOf<String>()
        val mismatched = linkedMapOf<String, String>()
        pins.forEach { (name, want) ->
            when (val got = installed[name]) {
                null -> missing += name
                want -> Unit
                else -> mismatched[name] = got
            }
        }
        return Verification(missing, mismatched)
    }

    /**
     * Extra hint appended to an `apk add` failure: a pin that no longer exists in the
     * live index fails with `unable to select packages` / `breaks: world[...]`.
     */
    fun failureHint(apkOutput: String): String = when {
        apkOutput.contains("breaks: world[") || apkOutput.contains("unable to select packages") ->
            " — a pinned version is no longer in the Alpine ${CosConstants.ALPINE_SERIES} index; " +
                "the app's pins must be bumped (see DesktopPackages)"
        apkOutput.contains("temporary error", ignoreCase = true) ||
            apkOutput.contains("could not connect", ignoreCase = true) ||
            apkOutput.contains("DNS", ignoreCase = true) -> " — check the network and retry"
        apkOutput.contains("No space left", ignoreCase = true) -> " — free some storage and retry"
        else -> ""
    }

    /** `( 12/140) Installing foo (1.0-r0)` -> (12, 140); null for any other line. */
    fun parseProgress(line: String): Pair<Int, Int>? {
        val m = PROGRESS_RE.find(line) ?: return null
        val done = m.groupValues[1].toInt()
        val total = m.groupValues[2].toInt()
        return if (total > 0 && done in 0..total) done to total else null
    }

    private val PROGRESS_RE = Regex("^\\(\\s*(\\d+)/(\\d+)\\)\\s")

    /**
     * Compares the index tarballs apk cached in the rootfs (`/var/cache/apk/APKINDEX.*.tar.gz`)
     * with the recorded snapshot for [arch]. Returns one log line.
     */
    fun indexSnapshotReport(apkCacheDir: File, arch: String): String {
        val known = APKINDEX_SHA256[arch]?.entries?.associate { (repo, sha) -> sha to repo }.orEmpty()
        val files = apkCacheDir.listFiles { f -> f.name.startsWith("APKINDEX.") && f.name.endsWith(".tar.gz") }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "APKINDEX snapshot: no cached index in ${apkCacheDir.path}"
        val parts = files.map { f ->
            val sha = sha256(f)
            val repo = known[sha]
            if (repo != null) "$repo=match" else "${f.name}=differs(${sha.take(12)})"
        }
        val allMatch = parts.size == known.size && parts.all { it.endsWith("=match") }
        return "APKINDEX snapshot ${if (allMatch) "matches" else "differs from"} pinned [$arch]: ${parts.joinToString()}"
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
