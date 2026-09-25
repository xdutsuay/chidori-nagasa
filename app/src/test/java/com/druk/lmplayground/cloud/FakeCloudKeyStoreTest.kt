package com.druk.lmplayground.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCloudKeyStoreTest {

    @Test
    fun `round trip set get and delete`() {
        val store = FakeCloudKeyStore()
        store.setKey("or", "sk-test")
        assertEquals("sk-test", store.getKey("or"))
        assertTrue(store.hasKey("or"))
        store.deleteKey("or")
        assertNull(store.getKey("or"))
        assertFalse(store.hasKey("or"))
    }

    @Test
    fun `blank key deletes entry`() {
        val store = FakeCloudKeyStore()
        store.setKey("or", "sk-test")
        store.setKey("or", "  ")
        assertNull(store.getKey("or"))
    }

    @Test
    fun `failed write throws CloudKeyStoreException`() {
        val store = FakeCloudKeyStore().apply { failWrites = true }
        assertThrows(CloudKeyStoreException::class.java) {
            store.setKey("or", "sk")
        }
    }
}
