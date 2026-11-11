package com.xdutsuay.chidori.computer.api

/**
 * Control actions for the Computer API (slice 2). Implementations block the calling
 * (server worker) thread; the routes call [approve] before every [exec] or [gui].
 */
interface ComputerControl {
    /** Asks the phone user; true only on an explicit Allow (deny, timeout and Stop are false). */
    fun approve(action: String, summary: String): Boolean

    /** Runs `cos_exec` with `{"command": ...}` and returns its JSON result. Never asks again. */
    fun exec(argumentsJson: String): String

    /** Runs `cos_gui` with its JSON arguments and returns its JSON result. Never asks again. */
    fun gui(argumentsJson: String): String
}
