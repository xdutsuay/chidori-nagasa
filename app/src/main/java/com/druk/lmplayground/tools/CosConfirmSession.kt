package com.druk.lmplayground.tools

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide Ask-once approval for the current chat generation session.
 * Reset when a new user turn starts (see GenerationCoordinator).
 */
object CosConfirmSession {
    private val approved = AtomicBoolean(false)

    fun isAskOnceApproved(): Boolean = approved.get()

    fun markAskOnceApproved() {
        approved.set(true)
    }

    fun reset() {
        approved.set(false)
    }
}
