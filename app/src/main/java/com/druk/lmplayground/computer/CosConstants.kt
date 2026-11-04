package com.druk.lmplayground.computer

/** Naming constants for the COS (computer OS) distro. */
object CosConstants {
    const val DISTRO_NAME = "COS"

    /** Sideload-first pinned Alpine minirootfs channel id. */
    const val ALPINE_CHANNEL = "alpine-minirootfs-pinned"

    const val PREFS_NAME = "computer_prefs"
    const val COS_DIR = "cos"
    const val READY_MARKER = ".cos_ready"

    /** Accounting placeholder until real tarball size is known. */
    const val STUB_ROOTFS_BUDGET_BYTES = 8L * 1024 * 1024

    /** Pinned Alpine minirootfs (sideload / download channel). */
    const val ALPINE_SERIES = "3.20"
    const val ALPINE_VERSION = "3.20.3"

    /** Soft estimate for full minirootfs download (~3–6 MiB compressed; budget headroom). */
    const val ALPINE_DOWNLOAD_BUDGET_BYTES = 32L * 1024 * 1024

    /**
     * Max wall time for a single [LocalComputerRuntime.execStreaming] process.
     * On timeout the process is destroyed and exit code 124 is returned.
     */
    const val EXEC_TIMEOUT_MS = 120_000L
}
