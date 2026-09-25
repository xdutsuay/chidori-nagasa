package com.druk.lmplayground.cloud

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudPresetsTest {

    @Test
    fun `https openrouter is valid`() {
        assertNull(CloudPresets.validateBaseUrl(CloudPresets.OPENROUTER.baseUrl, allowHttp = false))
    }

    @Test
    fun `http without toggle is rejected`() {
        assertNotNull(CloudPresets.validateBaseUrl("http://127.0.0.1:8080/v1", allowHttp = false))
    }

    @Test
    fun `http with toggle is allowed`() {
        assertNull(CloudPresets.validateBaseUrl("http://127.0.0.1:8080/v1", allowHttp = true))
    }

    @Test
    fun `empty and missing host are rejected`() {
        assertNotNull(CloudPresets.validateBaseUrl("  ", allowHttp = false))
        assertNotNull(CloudPresets.validateBaseUrl("https://", allowHttp = false))
        assertNotNull(CloudPresets.validateBaseUrl("ftp://example.com/v1", allowHttp = false))
    }
}
