package com.druk.lmplayground.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CP9 tests for the shared durable memory (per-user facts with
 * confirm/forget; no auto capture — plain CRUD only).
 *
 * Production symbols exercised (package com.druk.lmplayground.memory):
 *  - data class MemoryFact(id: String, text: String, confirmed: Boolean)
 *  - class DurableMemoryStore(prefs: SharedPreferences):
 *      putFact(id: String, text: String, confirmed: Boolean = false) // insert or replace
 *      getFact(id: String): MemoryFact?                              // null when absent
 *      listFacts(): List<MemoryFact>
 *      confirmFact(id: String)                                       // no-op when absent
 *      forgetFact(id: String)                                        // no-op when absent
 *  - const DurableMemoryStore.PREFS_NAME (SharedPreferences file name)
 *
 * No auto PII capture: nothing may be written by construction — a fresh store
 * on cleared prefs must be empty, and only explicit putFact calls add facts.
 */
@RunWith(RobolectricTestRunner::class)
class DurableMemoryStoreTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(DurableMemoryStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun newStore() = DurableMemoryStore(prefs)

    @Test
    fun `fresh store on cleared prefs is empty`() {
        assertTrue(newStore().listFacts().isEmpty())
        assertNull(newStore().getFact("anything"))
    }

    @Test
    fun `putFact then getFact round-trips id text and confirmed flag`() {
        val store = newStore()
        store.putFact("stack", "user prefers Kotlin", confirmed = false)
        store.putFact("editor", "user uses Neovim", confirmed = true)

        assertEquals(MemoryFact("stack", "user prefers Kotlin", false), store.getFact("stack"))
        assertEquals(MemoryFact("editor", "user uses Neovim", true), store.getFact("editor"))
    }

    @Test
    fun `getFact returns null for unknown id`() {
        assertNull(newStore().getFact("nope"))
    }

    @Test
    fun `listFacts returns every fact with id text and confirmed`() {
        val store = newStore()
        store.putFact("zebra", "z fact", confirmed = false)
        store.putFact("alpha", "a fact", confirmed = false)
        store.putFact("mid", "m fact", confirmed = true)

        val byId = store.listFacts().associateBy { it.id }
        assertEquals(setOf("zebra", "alpha", "mid"), byId.keys)
        assertEquals(MemoryFact("alpha", "a fact", false), byId["alpha"])
        assertEquals(MemoryFact("mid", "m fact", true), byId["mid"])
        assertEquals(MemoryFact("zebra", "z fact", false), byId["zebra"])
    }

    @Test
    fun `putFact replaces an existing fact with the same id`() {
        val store = newStore()
        store.putFact("stack", "old text", confirmed = false)
        store.putFact("stack", "new text", confirmed = true)

        assertEquals(1, store.listFacts().size)
        assertEquals(MemoryFact("stack", "new text", true), store.getFact("stack"))
    }

    @Test
    fun `confirmFact flips an unconfirmed fact to confirmed`() {
        val store = newStore()
        store.putFact("stack", "user prefers Kotlin", confirmed = false)

        store.confirmFact("stack")
        assertEquals(true, store.getFact("stack")?.confirmed)
        // Confirming again stays confirmed (idempotent).
        store.confirmFact("stack")
        assertEquals(true, store.getFact("stack")?.confirmed)
    }

    @Test
    fun `confirmFact is a no-op for unknown id`() {
        newStore().confirmFact("ghost")
        assertNull(newStore().getFact("ghost"))
        assertTrue(newStore().listFacts().isEmpty())
    }

    @Test
    fun `forgetFact removes the fact`() {
        val store = newStore()
        store.putFact("stack", "user prefers Kotlin", confirmed = true)

        store.forgetFact("stack")
        assertNull(store.getFact("stack"))
        assertTrue(store.listFacts().isEmpty())
    }

    @Test
    fun `forgetFact is a no-op for unknown id`() {
        newStore().forgetFact("ghost")
        assertTrue(newStore().listFacts().isEmpty())
    }

    @Test
    fun `facts survive a new store instance on the same prefs`() {
        val store = newStore()
        store.putFact("stack", "user prefers Kotlin", confirmed = false)
        store.putFact("editor", "user uses Neovim", confirmed = true)

        // "Session switch": a brand-new store instance over the same prefs.
        val reloaded = newStore()
        assertEquals(MemoryFact("stack", "user prefers Kotlin", false), reloaded.getFact("stack"))
        assertEquals(MemoryFact("editor", "user uses Neovim", true), reloaded.getFact("editor"))
        assertEquals(setOf("editor", "stack"), reloaded.listFacts().map { it.id }.toSet())
    }

    @Test
    fun `confirmed state persists across instances and forget is durable`() {
        val store = newStore()
        store.putFact("habit", "check CI before push", confirmed = false)
        store.confirmFact("habit")
        store.putFact("temp", "scratch note", confirmed = true)

        val reloaded = newStore()
        assertEquals(true, reloaded.getFact("habit")?.confirmed)

        reloaded.forgetFact("temp")
        // A third instance must not see the forgotten fact again.
        assertNull(newStore().getFact("temp"))
        assertEquals(1, newStore().listFacts().size)
    }
}
