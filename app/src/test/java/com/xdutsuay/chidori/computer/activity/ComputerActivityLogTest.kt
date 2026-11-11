package com.xdutsuay.chidori.computer.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerActivityLogTest {

    @Test
    fun `entries are newest first with clock time and increasing ids`() {
        var now = 1_000L
        val log = ComputerActivityLog(clock = { now })
        val a = log.record(Kind.COMMAND, "ls", Outcome.OK)
        now = 2_000L
        val b = log.record(Kind.GUI, "click 1,2", Outcome.ERROR, exitDetail(1))

        assertEquals(listOf(b, a), log.entries.value)
        assertEquals(1_000L, a.atMs)
        assertEquals(2_000L, b.atMs)
        assertTrue(b.id > a.id)
        assertEquals("exit 1", b.detail)
    }

    @Test
    fun `capacity drops the oldest entries`() {
        val log = ComputerActivityLog(capacity = 2, clock = { 0L })
        log.record(Kind.COMMAND, "one", Outcome.OK)
        log.record(Kind.COMMAND, "two", Outcome.OK)
        log.record(Kind.COMMAND, "three", Outcome.OK)

        assertEquals(listOf("three", "two"), log.entries.value.map { it.summary })
    }

    @Test
    fun `non-positive capacity still keeps the latest entry`() {
        val log = ComputerActivityLog(capacity = 0, clock = { 0L })
        log.record(Kind.COMMAND, "a", Outcome.OK)
        log.record(Kind.COMMAND, "b", Outcome.OK)

        assertEquals(listOf("b"), log.entries.value.map { it.summary })
    }

    @Test
    fun `clear empties the log but never reuses ids`() {
        val log = ComputerActivityLog(clock = { 0L })
        val first = log.record(Kind.SCREENSHOT, "", Outcome.OK)
        log.clear()
        assertTrue(log.entries.value.isEmpty())

        val next = log.record(Kind.SCREENSHOT, "", Outcome.OK)
        assertTrue(next.id > first.id)
    }

    @Test
    fun `forCommand collapses whitespace to one line`() {
        assertEquals("ls -la /tmp", ActivitySummary.forCommand("  ls\t-la\n\n /tmp  ", 50))
    }

    @Test
    fun `forCommand leaves a fitting command unchanged`() {
        assertEquals("abcde", ActivitySummary.forCommand("abcde", 5))
    }

    @Test
    fun `forCommand truncates to exactly maxLen with an ellipsis`() {
        val out = ActivitySummary.forCommand("abcdefghij", 5)
        assertEquals("abcd…", out)
        assertEquals(5, out.length)
    }

    @Test
    fun `forCommand edge cases`() {
        assertEquals("", ActivitySummary.forCommand("ls", 0))
        assertEquals("", ActivitySummary.forCommand("ls", -3))
        assertEquals("", ActivitySummary.forCommand(" \n\t ", 10))
        assertEquals("…", ActivitySummary.forCommand("ls -la", 1))
    }

    @Test
    fun `attached images are bounded and cleared with the log`() {
        ComputerActivity.clear()
        val ids = (1..9).map { ComputerActivity.log.record(Kind.SCREENSHOT, "", Outcome.OK).id }
        ids.forEach { ComputerActivity.attachImage(it, byteArrayOf(it.toByte())) }

        assertNull(ComputerActivity.imageFor(ids.first()))
        assertEquals(ids.last().toByte(), ComputerActivity.imageFor(ids.last())!![0])

        ComputerActivity.clear()
        assertNull(ComputerActivity.imageFor(ids.last()))
        assertTrue(ComputerActivity.log.entries.value.isEmpty())
    }
}
