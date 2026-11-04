package com.druk.lmplayground.computer

/**
 * Pure helpers for COS shell working-directory paths relative to the rootfs.
 * Paths use `/` separators; [DEFAULT] means the rootfs root.
 */
object CosCwd {
    const val DEFAULT = "."
    const val MAX_HISTORY = 50

    sealed class Result {
        data class Ok(val path: String) : Result()
        data class Err(val message: String) : Result()
    }

    /** Prompt label, e.g. `cos:/work $` or `cos:/ $` at root. */
    fun promptLabel(cwd: String): String {
        val display = when {
            cwd.isBlank() || cwd == DEFAULT -> "/"
            cwd.startsWith("/") -> cwd
            else -> "/$cwd"
        }
        return "cos:$display $"
    }

    /**
     * Resolve a `cd` target against [current] (relative to rootfs).
     * [argument] null/blank means go to root (`.`). Absolute targets start with `/`.
     */
    fun resolveCd(current: String, argument: String?): Result {
        val base = normalizeOrNull(current.ifBlank { DEFAULT })
            ?: return Result.Err("invalid cwd")
        val raw = argument?.trim().orEmpty()
        val joined = when {
            raw.isEmpty() -> DEFAULT
            raw.startsWith("/") -> raw.trimStart('/').ifEmpty { DEFAULT }
            base == DEFAULT -> raw
            else -> "$base/$raw"
        }
        val normalized = normalizeOrNull(joined)
            ?: return Result.Err("cd: path outside COS rootfs")
        return Result.Ok(normalized)
    }

    /**
     * Collapse `.` / `..` segments. Returns null if `..` would escape the rootfs.
     */
    fun normalizeOrNull(relative: String): String? {
        val parts = mutableListOf<String>()
        for (seg in relative.replace('\\', '/').split('/')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts += seg
            }
        }
        return if (parts.isEmpty()) DEFAULT else parts.joinToString("/")
    }
}
