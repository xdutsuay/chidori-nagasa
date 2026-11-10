package com.druk.lmplayground.computer.desktop

import com.druk.lmplayground.computer.CosConstants
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What the user must see before the desktop download starts: the size (always, from
 * [DesktopPackages] constants, before any byte is fetched), a metered / mobile-data
 * warning, and whether there is room. Pure so it is unit-tested; [readNetwork] is the
 * only Android-specific part.
 */
object DesktopInstallPolicy {

    /** Snapshot of the active network, from [ConnectivityManager]/[NetworkCapabilities]. */
    data class NetworkInfo(
        val connected: Boolean,
        /** `!NET_CAPABILITY_NOT_METERED` (also true for metered Wi‑Fi / hotspot). */
        val metered: Boolean,
        /** `TRANSPORT_CELLULAR`. */
        val cellular: Boolean,
    ) {
        companion object {
            val OFFLINE = NetworkInfo(connected = false, metered = false, cellular = false)
            val UNMETERED_WIFI = NetworkInfo(connected = true, metered = false, cellular = false)
        }
    }

    /** Free space we keep on top of the install (apk temp files, logs). */
    const val DISK_MARGIN_BYTES = 64L * 1024 * 1024

    data class Prompt(
        val title: String,
        val message: String,
        /** Download size shown before downloading. */
        val downloadBytes: Long,
        val installedBytes: Long,
        /** Show the metered / mobile warning and require an explicit confirm. */
        val meteredWarning: Boolean,
        /** Null when the install may proceed after confirm; otherwise why it cannot. */
        val blockedReason: String?,
        val confirmLabel: String,
    ) {
        val canInstall: Boolean get() = blockedReason == null
    }

    fun requiredFreeBytes(sets: List<DesktopPackages.PinnedSet>): Long =
        DesktopPackages.installedBytes(sets) + DesktopPackages.downloadBytes(sets) + DISK_MARGIN_BYTES

    /**
     * Builds the confirm dialog content.
     * @param usableBytes free bytes on the volume holding `filesDir` (`File.usableSpace`)
     */
    fun prompt(arch: String?, withFirefox: Boolean, network: NetworkInfo, usableBytes: Long): Prompt =
        prompt(arch, withFirefox, DesktopFlavor.OPENBOX, network, usableBytes)

    /** As above, for the chosen desktop [flavor] (XFCE adds its increment on top). */
    fun prompt(
        arch: String?,
        withFirefox: Boolean,
        flavor: DesktopFlavor,
        network: NetworkInfo,
        usableBytes: Long,
    ): Prompt {
        val sets = arch?.let { DesktopPackages.setsFor(it, withFirefox, flavor) }.orEmpty()
        val dl = DesktopPackages.downloadBytes(sets)
        val inst = DesktopPackages.installedBytes(sets)
        val metered = network.connected && (network.metered || network.cellular)
        val need = requiredFreeBytes(sets)
        val blocked = when {
            sets.isEmpty() -> "Desktop is not available for this CPU (${arch ?: "unknown"})"
            !network.connected -> "No network connection"
            usableBytes in 0 until need ->
                "Not enough free space: needs ${DesktopPackages.formatMiB(need)}, " +
                    "${DesktopPackages.formatMiB(usableBytes)} free"
            else -> null
        }
        val what = when {
            withFirefox && flavor == DesktopFlavor.XFCE -> "Desktop + XFCE + Firefox ESR"
            flavor == DesktopFlavor.XFCE -> "Desktop + XFCE (xfwm4, xfdesktop, panel, xfce4-terminal)"
            withFirefox -> "Desktop + Firefox ESR"
            else -> "Desktop (Xvfb, openbox, tint2, xterm, NetSurf, PCManFM)"
        }
        val message = buildString {
            append(what).append('\n')
            append("Download: ").append(DesktopPackages.formatMiB(dl))
            append(" · on disk: ").append(DesktopPackages.formatMiB(inst)).append('\n')
            append("Packages are pinned Alpine ${CosConstants.ALPINE_SERIES} builds, verified by apk signatures.")
            if (withFirefox) append("\nFirefox goes beyond the 512 MiB COS soft cap.")
            if (flavor == DesktopFlavor.XFCE) append("\nXFCE goes beyond the 512 MiB COS soft cap.")
            if (metered) {
                append("\n\n")
                append(
                    if (network.cellular) "You are on mobile data. This download may use your data plan."
                    else "This network is metered. This download may cost data.",
                )
            }
        }
        return Prompt(
            title = "Install desktop?",
            message = message,
            downloadBytes = dl,
            installedBytes = inst,
            meteredWarning = metered,
            blockedReason = blocked,
            confirmLabel = if (metered) "Download anyway (${DesktopPackages.approxMiB(dl)})" else "Download ${DesktopPackages.approxMiB(dl)}",
        )
    }

    /** Menu label, e.g. `Install desktop (~113 MiB)`. */
    fun menuLabel(arch: String?): String {
        val set = arch?.let { DesktopPackages.defaultSet(it) } ?: return "Install desktop"
        return "Install desktop (${DesktopPackages.approxMiB(set.downloadBytes)})"
    }

    /** Reads the active network; on any failure assumes metered (warn rather than not). */
    fun readNetwork(context: Context): NetworkInfo = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkInfo(connected = true, metered = true, cellular = false)
        val network = cm.activeNetwork ?: return NetworkInfo.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkInfo.OFFLINE
        fromCapabilities(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }.getOrDefault(NetworkInfo(connected = true, metered = true, cellular = false))

    fun fromCapabilities(hasInternet: Boolean, notMetered: Boolean, cellular: Boolean): NetworkInfo =
        NetworkInfo(connected = hasInternet, metered = !notMetered, cellular = cellular)
}
