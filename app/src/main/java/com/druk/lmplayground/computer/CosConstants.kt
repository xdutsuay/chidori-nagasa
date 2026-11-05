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

    /**
     * Pinned Alpine minirootfs (download channel). 3.20 went end-of-life on
     * 2026-04-01; 3.24 is supported until 2028-06-01.
     *
     * Bumping the version requires updating [ALPINE_SHA256] from the official
     * `.sha256` files next to the tarballs on dl-cdn.alpinelinux.org. Existing
     * installs whose ready marker records a different version are wiped and
     * re-provisioned automatically (see [CosProvisioning.needsReprovision]).
     */
    const val ALPINE_SERIES = "3.24"
    const val ALPINE_VERSION = "3.24.3"

    /** HTTPS-only CDN base; never fetch the rootfs over plain HTTP. */
    const val ALPINE_CDN_BASE = "https://dl-cdn.alpinelinux.org/alpine"

    /**
     * Extra HTTPS Alpine mirrors tried after [ALPINE_CDN_BASE] fails (CP4).
     * Same path layout: `/v{series}/releases/{arch}/{tarball}`.
     */
    val ALPINE_CDN_MIRRORS: List<String> = listOf(
        "https://mirror.math.princeton.edu/pub/alpinelinux",
        "https://mirrors.edge.kernel.org/alpine",
    )

    /**
     * Official sha256 of `alpine-minirootfs-[ALPINE_VERSION]-<arch>.tar.gz`, keyed by
     * Alpine arch. Sources:
     * - $ALPINE_CDN_BASE/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz.sha256
     * - $ALPINE_CDN_BASE/v3.24/releases/x86_64/alpine-minirootfs-3.24.3-x86_64.tar.gz.sha256
     * Hard-coded (not fetched at runtime) so a compromised mirror cannot swap both
     * the tarball and its hash.
     */
    val ALPINE_SHA256: Map<String, String> = mapOf(
        "aarch64" to "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50",
        "x86_64" to "f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407",
    )

    /** Nameservers written to the guest `/etc/resolv.conf` (minirootfs ships none). */
    val GUEST_NAMESERVERS: List<String> = listOf("1.1.1.1", "8.8.8.8")

    /** Guest home / default agent workspace inside COS. */
    const val GUEST_HOME = "/root"

    /** Alpine busybox shell inside the guest (NOT Android's /system/bin/sh). */
    const val GUEST_SH = "/bin/sh"

    /** Per-stream cap on captured stdout/stderr for one exec (bytes, approx chars). */
    const val EXEC_OUTPUT_CAP_CHARS = 256 * 1024

    /** Soft estimate for full minirootfs download (~3–6 MiB compressed; budget headroom). */
    const val ALPINE_DOWNLOAD_BUDGET_BYTES = 32L * 1024 * 1024

    /**
     * Max wall time for a single [LocalComputerRuntime.execStreaming] process.
     * On timeout the process is destroyed and exit code 124 is returned.
     */
    const val EXEC_TIMEOUT_MS = 120_000L
}
