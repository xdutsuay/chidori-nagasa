package com.druk.lmplayground.computer

/**
 * LRU-style prune planner for COS scratch / recipe layers under the soft cap.
 * Does not touch the disk — returns which relative paths to delete.
 */
object ComputerPruner {
    data class Entry(val relativePath: String, val bytes: Long, val lastUsedEpochMs: Long)

    /**
     * Returns paths to delete (oldest first) until [usedBytes] would be <= [softCapBytes],
     * or empty if no prune needed. Never proposes deleting paths in [protectedPaths].
     */
    fun planPrune(
        entries: List<Entry>,
        usedBytes: Long,
        softCapBytes: Long = ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES,
        protectedPaths: Set<String> = setOf("rootfs/.cos_ready", "rootfs"),
    ): List<String> {
        if (!ComputerStorageBudget.pruneNeeded(usedBytes, softCapBytes)) return emptyList()
        var remaining = usedBytes
        val victims = mutableListOf<String>()
        val candidates = entries
            .filter { it.relativePath !in protectedPaths && !it.relativePath.startsWith("rootfs/") }
            .sortedBy { it.lastUsedEpochMs }
        for (e in candidates) {
            if (remaining <= softCapBytes) break
            victims += e.relativePath
            remaining -= e.bytes.coerceAtLeast(0L)
        }
        return victims
    }
}
