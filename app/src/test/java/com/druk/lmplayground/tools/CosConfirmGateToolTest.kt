package com.druk.lmplayground.tools

import com.druk.lmplayground.computer.ComputerExecResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * F4: cos_gui / destructive cos_exec wait on a [CosConfirmGate] instead of
 * refusing. Approve lets the tool run; deny returns [CosConfirmPolicy.declinedJson].
 * The gate is injected as a `confirmGate` constructor parameter (assumption; see
 * the CINDI-agent handout contract).
 */
class CosConfirmGateToolTest {

    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    /** Blocks until the gate exposes a pending request (fail after 5s). */
    private fun awaitPending(gate: CosConfirmGate): CosConfirmGate.Pending {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            gate.pending.value?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("confirm gate never became pending")
    }

    // ---- cos_gui ----

    @Test
    fun `cos_gui with ALWAYS mode and an approving gate runs the action`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val bridge = FakeDesktopBridge()
        val tool = CosGuiTool(
            desktop = DesktopToolContext({ true }, { bridge }),
            confirmMode = { CosConfirmPolicy.Mode.ALWAYS },
            askOnceApproved = { false },
            confirmGate = gate,
        )
        val future = executor.submit<String> { tool.execute("""{"action":"click","x":10,"y":10}""") }
        val pending = awaitPending(gate)
        assertEquals("cos_gui", pending.toolName)
        gate.resolve(pending.id, approved = true)
        val r = JSONObject(future.get(5, TimeUnit.SECONDS))
        // The action actually ran through the desktop bridge.
        assertEquals(0, r.getInt("exit_code"))
        assertFalse(r.has("declined"))
        assertFalse(r.has("needs_confirmation"))
        assertFalse(bridge.commands.isEmpty())
    }

    @Test
    fun `cos_gui with ALWAYS mode and a denying gate returns declinedJson`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val bridge = FakeDesktopBridge()
        val tool = CosGuiTool(
            desktop = DesktopToolContext({ true }, { bridge }),
            confirmMode = { CosConfirmPolicy.Mode.ALWAYS },
            askOnceApproved = { false },
            confirmGate = gate,
        )
        val future = executor.submit<String> { tool.execute("""{"action":"type","text":"no"}""") }
        val pending = awaitPending(gate)
        gate.resolve(pending.id, approved = false)
        val r = JSONObject(future.get(5, TimeUnit.SECONDS))
        assertEquals(CosConfirmPolicy.declinedJson("cos_gui"), r.toString())
        assertEquals(1, r.getInt("exit_code"))
        assertTrue(r.getBoolean("declined"))
        assertTrue("denied command never reached the bridge", bridge.commands.isEmpty())
    }

    // ---- cos_exec (destructive command) ----

    private fun destructiveExecArgs() = """{"command":"rm -rf /tmp/build"}"""

    @Test
    fun `cos_exec with ALWAYS mode and an approving gate runs the command`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val tool = CosExecTool(
            runner = { command -> ComputerExecResult(0, "out:$command", "") },
            confirmMode = { CosConfirmPolicy.Mode.ALWAYS },
            askOnceApproved = { false },
            confirmGate = gate,
        )
        val future = executor.submit<String> { tool.execute(destructiveExecArgs()) }
        val pending = awaitPending(gate)
        gate.resolve(pending.id, approved = true)
        val r = JSONObject(future.get(5, TimeUnit.SECONDS))
        assertEquals(0, r.getInt("exit_code"))
        assertEquals("out:rm -rf /tmp/build", r.getString("stdout"))
        assertFalse(r.has("declined"))
        assertFalse(r.has("needs_confirmation"))
    }

    @Test
    fun `cos_exec with ALWAYS mode and a denying gate returns declinedJson`() {
        val gate = CosConfirmGate(timeoutMs = 10_000)
        val tool = CosExecTool(
            runner = { _ -> ComputerExecResult(0, "should not run", "") },
            confirmMode = { CosConfirmPolicy.Mode.ALWAYS },
            askOnceApproved = { false },
            confirmGate = gate,
        )
        val future = executor.submit<String> { tool.execute(destructiveExecArgs()) }
        val pending = awaitPending(gate)
        assertEquals("cos_exec", pending.toolName)
        gate.resolve(pending.id, approved = false)
        val r = JSONObject(future.get(5, TimeUnit.SECONDS))
        assertEquals(CosConfirmPolicy.declinedJson("cos_exec"), r.toString())
    }
}
