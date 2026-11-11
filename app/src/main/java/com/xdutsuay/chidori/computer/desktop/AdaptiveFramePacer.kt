package com.xdutsuay.chidori.computer.desktop

/**
 * Poll interval for the live desktop preview: full rate while the screen is
 * changing or the user is touching it, exponential backoff while it is idle.
 */
class AdaptiveFramePacer(
    private val minIntervalMs: Long = 66L,
    private val maxIntervalMs: Long = 500L,
) {
    init {
        require(minIntervalMs > 0) { "minIntervalMs must be > 0" }
        require(maxIntervalMs >= minIntervalMs) { "maxIntervalMs must be >= minIntervalMs" }
    }

    var currentIntervalMs: Long = minIntervalMs
        private set

    fun onFrame(changed: Boolean): Long {
        currentIntervalMs = if (changed) {
            minIntervalMs
        } else {
            minOf(currentIntervalMs * 2, maxIntervalMs)
        }
        return currentIntervalMs
    }

    fun onInput() {
        currentIntervalMs = minIntervalMs
    }
}

/** Detects whether a decoded framebuffer differs from the last accepted one. */
class FrameChangeDetector {
    private var width = -1
    private var height = -1
    private var pixels: IntArray? = null

    fun isNew(width: Int, height: Int, argb: IntArray): Boolean {
        val prev = pixels
        if (prev != null && width == this.width && height == this.height && prev.contentEquals(argb)) {
            return false
        }
        this.width = width
        this.height = height
        pixels = argb.copyOf()
        return true
    }

    fun reset() {
        pixels = null
        width = -1
        height = -1
    }
}
