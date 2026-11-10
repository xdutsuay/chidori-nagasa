package com.druk.lmplayground.computer.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteComputerValidationTest {

    private fun validate(
        name: String = "Lab",
        host: String = "203.0.113.7",
        port: Int = 22,
        user: String = "ubuntu",
    ): Set<RemoteComputerValidation.Field> =
        RemoteComputerValidation.validate(name, host, port, user)

    @Test
    fun `valid minimal example returns empty set`() {
        assertTrue(validate().isEmpty())
    }

    @Test
    fun `valid aws-like example returns empty set`() {
        assertTrue(
            validate(
                name = "aws",
                host = "ec2-1-2-3-4.compute.amazonaws.com",
                port = 2222,
                user = "ec2-user",
            ).isEmpty(),
        )
    }

    @Test
    fun `inputs are validated after trim`() {
        assertTrue(validate(name = "  Lab  ", host = " 203.0.113.7 ", user = " ubuntu ").isEmpty())
    }

    @Test
    fun `ipv6 literal host is valid`() {
        assertTrue(validate(host = "2001:db8::1").isEmpty())
    }

    @Test
    fun `name blank is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.NAME),
            validate(name = ""),
        )
    }

    @Test
    fun `name whitespace only is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.NAME),
            validate(name = "   "),
        )
    }

    @Test
    fun `name longer than 40 chars is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.NAME),
            validate(name = "a".repeat(41)),
        )
        assertTrue(validate(name = "a".repeat(40)).isEmpty())
    }

    @Test
    fun `host blank is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.HOST),
            validate(host = ""),
        )
    }

    @Test
    fun `host longer than 253 chars is invalid`() {
        val longHost = "a".repeat(250) + ".com"
        assertTrue(longHost.length > 253)
        assertEquals(
            setOf(RemoteComputerValidation.Field.HOST),
            validate(host = longHost),
        )
    }

    @Test
    fun `host with interior whitespace is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.HOST),
            validate(host = "a b"),
        )
    }

    @Test
    fun `host with scheme separator is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.HOST),
            validate(host = "ssh://x"),
        )
    }

    @Test
    fun `host with slash is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.HOST),
            validate(host = "host/path"),
        )
    }

    @Test
    fun `host with userinfo at sign is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.HOST),
            validate(host = "user@host"),
        )
    }

    @Test
    fun `port zero is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.PORT),
            validate(port = 0),
        )
    }

    @Test
    fun `port above range is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.PORT),
            validate(port = 70000),
        )
    }

    @Test
    fun `port boundaries are valid`() {
        assertTrue(validate(port = 1).isEmpty())
        assertTrue(validate(port = 65535).isEmpty())
    }

    @Test
    fun `user blank is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.USER),
            validate(user = ""),
        )
    }

    @Test
    fun `user starting with digit is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.USER),
            validate(user = "1abc"),
        )
    }

    @Test
    fun `user with interior whitespace is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.USER),
            validate(user = "a b"),
        )
    }

    @Test
    fun `user longer than 32 chars is invalid`() {
        assertEquals(
            setOf(RemoteComputerValidation.Field.USER),
            validate(user = "a".repeat(33)),
        )
        assertTrue(validate(user = "a".repeat(32)).isEmpty())
    }

    @Test
    fun `user with allowed punctuation is valid`() {
        assertTrue(validate(user = "a.b-c_d").isEmpty())
    }

    @Test
    fun `multiple invalid fields are all reported`() {
        assertEquals(
            setOf(
                RemoteComputerValidation.Field.NAME,
                RemoteComputerValidation.Field.PORT,
                RemoteComputerValidation.Field.USER,
            ),
            validate(name = "", port = 0, user = "1abc"),
        )
    }
}
