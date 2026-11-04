package com.druk.lmplayground.computer

/**
 * Soft-cap accounting for computer storage (rootfs + model scratch space).
 * Soft cap: downloads that would push usage past the cap are rejected, and
 * pruning kicks in once usage alone exceeds it.
 */
object ComputerStorageBudget {
    const val DEFAULT_SOFT_CAP_BYTES: Long = 512L * 1024 * 1024 // 512 MiB

    /** True if admitting [incomingBytes] on top of [usedBytes] would exceed the soft cap. */
    fun wouldExceed(
        usedBytes: Long,
        incomingBytes: Long,
        softCapBytes: Long = DEFAULT_SOFT_CAP_BYTES,
    ): Boolean = usedBytes + incomingBytes > softCapBytes

    /** True if current usage already exceeds the soft cap (pruning required). */
    fun pruneNeeded(
        usedBytes: Long,
        softCapBytes: Long = DEFAULT_SOFT_CAP_BYTES,
    ): Boolean = usedBytes > softCapBytes
}
