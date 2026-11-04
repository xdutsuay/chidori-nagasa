package com.druk.lmplayground.computer

import java.util.Locale

/**
 * Pure helpers for COS rootfs ready-marker parsing and human-readable sizes.
 * Marker lines look like `mode=alpine-tar` / `mode=offline-stub`.
 */
object CosRootfsInfo {
    const val MODE_ALPINE_TAR = "alpine-tar"
    const val MODE_OFFLINE_STUB = "offline-stub"

    /** Short UI labels matching `Rootfs: alpine|stub · …`. */
    const val LABEL_ALPINE = "alpine"
    const val LABEL_STUB = "stub"
    const val LABEL_NONE = "none"

    /**
     * Reads `mode=` from ready-marker text. Returns null when absent/blank.
     */
    fun parseMode(markerText: String): String? {
        for (raw in markerText.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("mode=")) continue
            val value = line.substringAfter("mode=", "").trim()
            return value.ifEmpty { null }
        }
        return null
    }

    fun shortModeLabel(mode: String?): String = when (mode) {
        null, "" -> LABEL_NONE
        MODE_ALPINE_TAR -> LABEL_ALPINE
        MODE_OFFLINE_STUB -> LABEL_STUB
        else -> mode
    }

    /** Human-readable size, e.g. `12.4 MB`, `512 B`, `1.0 KB`. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    /** Status line: `Rootfs: alpine|stub · 12.4 MB`. */
    fun statusLine(mode: String?, usedBytes: Long): String =
        "Rootfs: ${shortModeLabel(mode)} · ${formatBytes(usedBytes)}"
}

/** Snapshot for the Computer screen (mode from marker + used bytes under cos/). */
data class CosRootfsSnapshot(
    val mode: String?,
    val usedBytes: Long,
    val present: Boolean,
) {
    val statusLine: String get() = CosRootfsInfo.statusLine(mode, usedBytes)
}
