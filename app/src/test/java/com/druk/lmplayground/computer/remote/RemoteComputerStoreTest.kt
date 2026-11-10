package com.druk.lmplayground.computer.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** In-memory fake of [RemoteComputerBackend] so tests never touch a real persistence layer. */
private class FakeRemoteComputerBackend : RemoteComputerBackend {
    var stored: String? = null
    var writeCount: Int = 0

    override fun read(): String? = stored

    override fun write(json: String) {
        stored = json
        writeCount++
    }
}

@RunWith(RobolectricTestRunner::class)
class RemoteComputerStoreTest {

    private lateinit var backend: FakeRemoteComputerBackend

    @Before
    fun setUp() {
        backend = FakeRemoteComputerBackend()
    }

    private fun newStore(): RemoteComputerStore =
        RemoteComputerStore(backend) { "id-${backend.writeCount + 1}" }

    @Test
    fun `list returns empty when backend read is null`() {
        assertTrue(newStore().list().isEmpty())
    }

    @Test
    fun `list returns empty when backend read is blank`() {
        backend.stored = "   "
        assertTrue(newStore().list().isEmpty())
    }

    @Test
    fun `list returns empty on corrupt json without throwing`() {
        backend.stored = "{not json at all"
        assertTrue(newStore().list().isEmpty())
    }

    @Test
    fun `add trims inputs and persists with generated id`() {
        val store = newStore()
        val added = store.add("  Lab  ", " 203.0.113.7 ", 22, " ubuntu ")

        assertEquals("id-1", added.id)
        assertEquals("Lab", added.name)
        assertEquals("203.0.113.7", added.host)
        assertEquals(22, added.port)
        assertEquals("ubuntu", added.user)
        assertNull(added.hostKeyFingerprint)
        assertNotNull(backend.stored)
    }

    @Test
    fun `add rejects invalid input with IllegalArgumentException`() {
        val store = newStore()
        assertInvalid { store.add("", "203.0.113.7", 22, "ubuntu") }
        assertInvalid { store.add("Lab", "ssh://x", 22, "ubuntu") }
        assertInvalid { store.add("Lab", "203.0.113.7", 0, "ubuntu") }
        assertInvalid { store.add("Lab", "203.0.113.7", 22, "1abc") }
    }

    private inline fun assertInvalid(block: () -> Unit) {
        var thrown: IllegalArgumentException? = null
        try {
            block()
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("expected IllegalArgumentException", thrown)
    }

    @Test
    fun `add does not persist when validation fails`() {
        val store = newStore()
        try {
            store.add("", "203.0.113.7", 22, "ubuntu")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertNull(backend.stored)
    }

    @Test
    fun `list preserves insertion order`() {
        val store = newStore()
        store.add("first", "203.0.113.7", 22, "ubuntu")
        store.add("second", "203.0.113.8", 2222, "ec2-user")
        store.add("third", "2001:db8::1", 22, "root")

        val listed = store.list()
        assertEquals(3, listed.size)
        assertEquals("first", listed[0].name)
        assertEquals("second", listed[1].name)
        assertEquals("third", listed[2].name)
        assertEquals(2222, listed[1].port)
    }

    @Test
    fun `get returns added computer by id`() {
        val store = newStore()
        val added = store.add("Lab", "203.0.113.7", 22, "ubuntu")

        assertEquals(added, store.get(added.id))
    }

    @Test
    fun `get returns null for unknown id`() {
        val store = newStore()
        store.add("Lab", "203.0.113.7", 22, "ubuntu")

        assertNull(store.get("no-such-id"))
    }

    @Test
    fun `pinHostKey sets fingerprint and persists`() {
        val store = newStore()
        val added = store.add("Lab", "203.0.113.7", 22, "ubuntu")

        val pinned = store.pinHostKey(added.id, "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU")

        assertNotNull(pinned)
        assertEquals(
            "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU",
            pinned!!.hostKeyFingerprint,
        )
        assertEquals(pinned, store.get(added.id))
    }

    @Test
    fun `pinHostKey returns null for unknown id`() {
        val store = newStore()

        assertNull(store.pinHostKey("no-such-id", "SHA256:abc"))
    }

    @Test
    fun `clearHostKey sets fingerprint back to null`() {
        val store = newStore()
        val added = store.add("Lab", "203.0.113.7", 22, "ubuntu")
        store.pinHostKey(added.id, "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU")

        val cleared = store.clearHostKey(added.id)

        assertNotNull(cleared)
        assertNull(cleared!!.hostKeyFingerprint)
        assertEquals(cleared, store.get(added.id))
    }

    @Test
    fun `clearHostKey returns null for unknown id`() {
        val store = newStore()

        assertNull(store.clearHostKey("no-such-id"))
    }

    @Test
    fun `remove returns true and drops the entry`() {
        val store = newStore()
        val added = store.add("Lab", "203.0.113.7", 22, "ubuntu")

        assertTrue(store.remove(added.id))
        assertNull(store.get(added.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `remove returns false for unknown id`() {
        assertFalse(newStore().remove("no-such-id"))
    }

    @Test
    fun `new store instance over same backend reads back identical data`() {
        val first = newStore()
        val a = first.add("Lab", "203.0.113.7", 22, "ubuntu")
        val b = first.add("aws", "ec2-1-2-3-4.compute.amazonaws.com", 2222, "ec2-user")
        val fp = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU"
        val aPinned = first.pinHostKey(a.id, fp)!!

        val second = newStore()
        val roundTripped = second.list()

        assertEquals(2, roundTripped.size)
        assertEquals(aPinned, roundTripped[0])
        assertEquals(b, roundTripped[1])
        assertEquals(fp, roundTripped[0].hostKeyFingerprint)
        assertNull(roundTripped[1].hostKeyFingerprint)
        assertEquals(22, roundTripped[0].port)
        assertEquals(2222, roundTripped[1].port)

        assertEquals(aPinned, second.get(a.id))
        assertEquals(b, second.get(b.id))
    }
}
