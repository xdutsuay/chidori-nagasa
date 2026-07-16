package com.druk.lmplayground.coordinator.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolVersionTest {

    @Test
    fun `CURRENT protocol version is 1_2_0`() {
        assertEquals("1.2.0", ProtocolVersion.CURRENT.value)
    }

    @Test
    fun `DEFAULT_COMPANION_PORT is 8027`() {
        assertEquals(8027, ProtocolVersion.DEFAULT_COMPANION_PORT)
    }
}
