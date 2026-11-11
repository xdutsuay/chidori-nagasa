package com.xdutsuay.chidori.tools

import com.xdutsuay.chidori.computer.ComputerExecResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosExecFormatterTest {

    @Test
    fun parseCommandExtractsString() {
        assertEquals("uname -a", CosExecFormatter.parseCommand("""{"command":"uname -a"}"""))
    }

    @Test
    fun parseCommandMissingKeyIsNull() {
        assertNull(CosExecFormatter.parseCommand("""{"cmd":"ls"}"""))
    }

    @Test
    fun parseCommandInvalidJsonIsNull() {
        assertNull(CosExecFormatter.parseCommand("not-json"))
    }

    @Test
    fun formatResultIncludesStdoutStderrExit() {
        val json = CosExecFormatter.formatResult(
            ComputerExecResult(0, "hello\n", ""),
        )
        val obj = JSONObject(json)
        assertEquals(0, obj.getInt("exit_code"))
        assertEquals("hello\n", obj.getString("stdout"))
        assertEquals("", obj.getString("stderr"))
        assertFalse(obj.has("error"))
    }

    @Test
    fun formatResultAddsClearErrorWhenCosDisabled() {
        val json = CosExecFormatter.formatResult(
            ComputerExecResult(127, "", "COS disabled — enable Computer first"),
        )
        val obj = JSONObject(json)
        assertEquals(127, obj.getInt("exit_code"))
        assertEquals(CosExecFormatter.DISABLED_ERROR, obj.getString("error"))
        assertTrue(CosExecFormatter.isCosDisabled(
            ComputerExecResult(127, "", "COS disabled — enable Computer first"),
        ))
    }

    @Test
    fun errorJsonWrapsMessage() {
        val obj = JSONObject(CosExecFormatter.errorJson("empty command"))
        assertEquals("empty command", obj.getString("error"))
    }
}
