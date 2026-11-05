package com.druk.lmplayground.tools

interface Tool {
    val name: String
    val description: String
    val parametersSchema: String
    fun execute(arguments: String): String

    /**
     * Abort any in-flight work (e.g. a blocking network request) so a Stop
     * during tool execution returns promptly. Default no-op for tools with
     * nothing to cancel (e.g. JavaScript).
     */
    fun cancelInFlight() {}

    /**
     * Whether the tool can run right now, independent of the user's toggle (e.g.
     * the desktop tools need the COS desktop to be running). Unavailable tools are
     * left out of the schema sent to the model; a tool called anyway must check
     * again in [execute] and return an error without doing anything. Must be
     * cheap: it is evaluated on every turn.
     */
    fun isAvailable(): Boolean = true
}
