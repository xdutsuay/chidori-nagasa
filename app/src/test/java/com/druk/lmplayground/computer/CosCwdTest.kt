package com.druk.lmplayground.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosCwdTest {
    @Test
    fun `default and blank normalize to root`() {
        assertEquals(CosCwd.DEFAULT, CosCwd.normalizeOrNull("."))
        assertEquals(CosCwd.DEFAULT, CosCwd.normalizeOrNull(""))
        assertEquals(CosCwd.DEFAULT, CosCwd.normalizeOrNull("././"))
    }

    @Test
    fun `relative cd from root`() {
        val r = CosCwd.resolveCd(".", "work")
        assertTrue(r is CosCwd.Result.Ok)
        assertEquals("work", (r as CosCwd.Result.Ok).path)
    }

    @Test
    fun `relative cd nests`() {
        val r = CosCwd.resolveCd("work", "src")
        assertTrue(r is CosCwd.Result.Ok)
        assertEquals("work/src", (r as CosCwd.Result.Ok).path)
    }

    @Test
    fun `absolute cd within rootfs`() {
        val r = CosCwd.resolveCd("work/src", "/tmp")
        assertTrue(r is CosCwd.Result.Ok)
        assertEquals("tmp", (r as CosCwd.Result.Ok).path)
    }

    @Test
    fun `absolute slash is root`() {
        val r = CosCwd.resolveCd("work", "/")
        assertTrue(r is CosCwd.Result.Ok)
        assertEquals(CosCwd.DEFAULT, (r as CosCwd.Result.Ok).path)
    }

    @Test
    fun `cd blank goes to root`() {
        val r = CosCwd.resolveCd("work/src", null)
        assertTrue(r is CosCwd.Result.Ok)
        assertEquals(CosCwd.DEFAULT, (r as CosCwd.Result.Ok).path)
    }

    @Test
    fun `cd dotdot walks up`() {
        val r = CosCwd.resolveCd("work/src", "..")
        assertTrue(r is CosCwd.Result.Ok)
        assertEquals("work", (r as CosCwd.Result.Ok).path)
    }

    @Test
    fun `cd outside rootfs rejected`() {
        val r = CosCwd.resolveCd(".", "..")
        assertTrue(r is CosCwd.Result.Err)
    }

    @Test
    fun `cd many dotdot from nested rejected`() {
        val r = CosCwd.resolveCd("a/b", "../../..")
        assertTrue(r is CosCwd.Result.Err)
    }

    @Test
    fun `prompt label`() {
        assertEquals("cos:/ $", CosCwd.promptLabel("."))
        assertEquals("cos:/work $", CosCwd.promptLabel("work"))
        assertEquals("cos:/work/src $", CosCwd.promptLabel("work/src"))
    }
}
