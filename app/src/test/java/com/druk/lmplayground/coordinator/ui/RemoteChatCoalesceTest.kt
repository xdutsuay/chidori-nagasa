package com.druk.lmplayground.coordinator.ui

import com.druk.lmplayground.coordinator.model.RemoteChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteChatCoalesceTest {

    @Test
    fun `consecutive assistant frames append into one bubble`() {
        val first = msg(id = "a1", fromUser = false, text = "It")
        val second = msg(id = "a2", fromUser = false, text = " seems")
        val third = msg(id = "a3", fromUser = false, text = " like")

        val after = listOf(first)
            .let { coalesceRemoteChat(it, second) }
            .let { coalesceRemoteChat(it, third) }

        assertEquals(1, after.size)
        assertEquals("a1", after.single().id)
        assertEquals("It seems like", after.single().text)
    }

    @Test
    fun `user echo starts a new bubble after assistant`() {
        val assistant = msg(id = "a1", fromUser = false, text = "hi")
        val user = msg(id = "u1", fromUser = true, text = "yo")

        val after = coalesceRemoteChat(listOf(assistant), user)

        assertEquals(2, after.size)
        assertEquals("yo", after.last().text)
    }

    @Test
    fun `same id replaces in place`() {
        val first = msg(id = "same", fromUser = false, text = "Hel")
        val grown = msg(id = "same", fromUser = false, text = "Hello")

        val after = coalesceRemoteChat(listOf(first), grown)

        assertEquals(1, after.size)
        assertEquals("Hello", after.single().text)
    }

    @Test
    fun `assistant after user starts a new bubble`() {
        val user = msg(id = "u1", fromUser = true, text = "gh")
        val assistant = msg(id = "a1", fromUser = false, text = "It")

        val after = coalesceRemoteChat(listOf(user), assistant)

        assertEquals(2, after.size)
        assertEquals("It", after.last().text)
    }

    private fun msg(id: String, fromUser: Boolean, text: String) = RemoteChatMessage(
        id = id,
        fromUser = fromUser,
        text = text,
        sentAtEpochMillis = 1L,
    )
}
