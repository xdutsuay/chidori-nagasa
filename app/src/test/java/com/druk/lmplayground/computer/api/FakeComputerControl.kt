package com.druk.lmplayground.computer.api

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recording [ComputerControl] for slice-2 tests: every [approve] call is recorded with its
 * exact action/summary, exec/gui argument JSON is recorded, and the approval outcome plus the
 * canned JSON results are configurable per test.
 */
class FakeComputerControl(
    var approved: Boolean = true,
    var execResultJson: String = """{"ok":true,"source":"exec"}""",
    var guiResultJson: String = """{"ok":true,"source":"gui"}""",
) : ComputerControl {

    data class ApprovalCall(val action: String, val summary: String)

    val approvals = CopyOnWriteArrayList<ApprovalCall>()
    val execCalls = CopyOnWriteArrayList<String>()
    val guiCalls = CopyOnWriteArrayList<String>()

    override fun approve(action: String, summary: String): Boolean {
        approvals += ApprovalCall(action, summary)
        return approved
    }

    override fun exec(argumentsJson: String): String {
        execCalls += argumentsJson
        return execResultJson
    }

    override fun gui(argumentsJson: String): String {
        guiCalls += argumentsJson
        return guiResultJson
    }
}
