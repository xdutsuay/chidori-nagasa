package com.xdutsuay.chidori.computer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ProcessBuilder

/**
 * CP3 build gate: pid capture must keep working via [Process.pid] (reflective) or the
 * private `pid` field on the Process impl. If a future runtime blocks both paths,
 * this test fails the unit-test build so we notice before on-device Stop/kill breaks.
 */
class PidReflectionGateTest {

    @Test
    fun `Process pid is discoverable via public pid method or private field`() {
        // Windows hosts have no `true` binary; `cmd /c exit 0` is portable enough for the gate.
        val proc = ProcessBuilder("cmd", "/c", "exit", "0").start().also { it.waitFor() }
        val hasMethod = runCatching { Process::class.java.getMethod("pid") }.isSuccess
        var hasField = false
        var c: Class<*>? = proc.javaClass
        while (c != null) {
            if (c.declaredFields.any { it.name == "pid" }) {
                hasField = true
                break
            }
            c = c.superclass
        }
        assertTrue(
            "need Process.pid() or a private pid field on ${proc.javaClass.name}",
            hasMethod || hasField,
        )
        val resolved = ProotTerminator.pidOf(proc)
        assertTrue("pidOf must resolve a live/recent process (got $resolved)", resolved > 0)
    }
}
