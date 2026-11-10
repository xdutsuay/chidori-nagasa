package com.druk.lmplayground.checkin

import com.druk.lmplayground.inference.ModelRuntime

/**
 * The conversation screen's loaded [ModelRuntime], if one exists.
 *
 * Check-in extraction calls [ModelRuntime.nodeModeComplete] on it so the
 * chat session's KV cache is not reused. WorkManager can start the process
 * with no screen up; the slot is then empty and extraction is queued.
 */
object LoadedModelSlot {
    @Volatile
    var runtime: ModelRuntime? = null
        private set

    fun attach(runtime: ModelRuntime) {
        this.runtime = runtime
    }

    fun detach(runtime: ModelRuntime) {
        if (this.runtime === runtime) this.runtime = null
    }
}
