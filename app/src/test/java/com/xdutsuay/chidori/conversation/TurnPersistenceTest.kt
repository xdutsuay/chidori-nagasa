package com.xdutsuay.chidori.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the contract of [TurnPersistence], the pure-Kotlin helper that owns
 * turn-persistence decisions extracted from ConversationViewModel:
 *
 * - the assistant reply is persisted BEFORE the "Response ready" announcement,
 *   even when persistence fails;
 * - save failures surface via a callback instead of being swallowed;
 * - a failed user-turn persist cannot leave the chat stuck generating;
 * - the image file is only persisted after the message-size gate passes.
 */
class TurnPersistenceTest {

    // ------------------------------------------------------------------
    // finishAssistantTurn
    // ------------------------------------------------------------------

    @Test
    fun finishAssistantTurnPersistsBeforeAnnounceAndReturnsTrue() = runTest {
        val log = mutableListOf<String>()
        val result = TurnPersistence.finishAssistantTurn(
            persist = { log.add("persist") },
            announce = { log.add("announce") },
            onSaveFailed = { log.add("saveFailed") },
        )

        assertTrue(result)
        // persist strictly first, then exactly one announce; no save failure.
        assertEquals(listOf("persist", "announce"), log)
    }

    @Test
    fun finishAssistantTurnPersistRunsExactlyOnceOnSuccess() = runTest {
        var persistCalls = 0
        TurnPersistence.finishAssistantTurn(
            persist = { persistCalls++ },
            announce = {},
            onSaveFailed = {},
        )

        assertEquals(1, persistCalls)
    }

    @Test
    fun finishAssistantTurnOnSaveFailureCallsOnSaveFailedBeforeAnnounceAndReturnsFalse() = runTest {
        val log = mutableListOf<String>()
        val failure = IllegalStateException("disk full")
        val result = TurnPersistence.finishAssistantTurn(
            persist = { log.add("persist"); throw failure },
            announce = { log.add("announce") },
            onSaveFailed = { log.add("saveFailed:${it.message}") },
        )

        // Does not throw; reports failure instead.
        assertFalse(result)
        // saveFailed exactly once, with the thrown exception, BEFORE announce;
        // the user still hears the completion chime after a failed save.
        assertEquals(listOf("persist", "saveFailed:disk full", "announce"), log)
    }

    @Test
    fun finishAssistantTurnOnSaveFailureStillAnnouncesExactlyOnce() = runTest {
        var announceCalls = 0
        TurnPersistence.finishAssistantTurn(
            persist = { throw IllegalStateException("disk full") },
            announce = { announceCalls++ },
            onSaveFailed = { },
        )

        assertEquals(1, announceCalls)
    }

    @Test
    fun finishAssistantTurnPropagatesCancellationWithoutAnnounceOrSaveFailed() = runTest {
        val log = mutableListOf<String>()
        val deferred = async {
            TurnPersistence.finishAssistantTurn(
                persist = { log.add("persist"); throw CancellationException("job cancelled") },
                announce = { log.add("announce") },
                onSaveFailed = { log.add("saveFailed") },
            )
        }

        var caught: Throwable? = null
        try {
            deferred.await()
        } catch (e: CancellationException) {
            caught = e
        }

        // Cancellation propagates to the caller...
        assertTrue("expected CancellationException, got $caught", caught is CancellationException)
        // ...without announcing or reporting a save failure.
        assertEquals(listOf("persist"), log)
    }

    // ------------------------------------------------------------------
    // beginUserTurn
    // ------------------------------------------------------------------

    @Test
    fun beginUserTurnReturnsPersistedStringOnSuccess() = runTest {
        val log = mutableListOf<String>()
        val result = TurnPersistence.beginUserTurn(
            persistUser = { log.add("persistUser"); "msg-id-1" },
            onFailed = { log.add("failed") },
        )

        assertEquals("msg-id-1", result)
        assertEquals(listOf("persistUser"), log)
    }

    @Test
    fun beginUserTurnOnFailureCallsOnFailedOnceAndReturnsNull() = runTest {
        val log = mutableListOf<String>()
        val failure = IllegalStateException("db locked")
        var onFailedCalls = 0

        val result = TurnPersistence.beginUserTurn(
            persistUser = { log.add("persistUser"); throw failure },
            onFailed = { onFailedCalls++; log.add("failed:${it.message}") },
        )

        // Does not throw; the chat must not be stuck generating.
        assertNull(result)
        assertEquals(1, onFailedCalls)
        // onFailed receives the original exception, exactly once.
        assertEquals(listOf("persistUser", "failed:db locked"), log)
    }

    @Test
    fun beginUserTurnPropagatesCancellationWithoutCallingOnFailed() = runTest {
        val log = mutableListOf<String>()
        val deferred = async {
            TurnPersistence.beginUserTurn(
                persistUser = { log.add("persistUser"); throw CancellationException("scope cancelled") },
                onFailed = { log.add("failed") },
            )
        }

        var caught: Throwable? = null
        try {
            deferred.await()
        } catch (e: CancellationException) {
            caught = e
        }

        assertTrue("expected CancellationException, got $caught", caught is CancellationException)
        assertEquals(listOf("persistUser"), log)
    }

    // ------------------------------------------------------------------
    // gateImage
    // ------------------------------------------------------------------

    @Test
    fun gateImageTooLargeReturnsTooLargeWithoutPersisting() {
        val log = mutableListOf<String>()
        // 100 chars * 2 bytes = 200 > 199.
        val gate = TurnPersistence.gateImage(
            contentChars = 100,
            maxPayloadBytes = 199,
            persistImage = { log.add("persistImage"); "stored.jpg" },
        )

        assertTrue(gate is ImageGate.TooLarge)
        // Oversized message must NOT leave an orphaned image file.
        assertTrue(log.isEmpty())
    }

    @Test
    fun gateImageAtExactLimitPersistsAndFits() {
        val log = mutableListOf<String>()
        // 100 chars * 2 bytes = 200 == 200: equal is allowed.
        val gate = TurnPersistence.gateImage(
            contentChars = 100,
            maxPayloadBytes = 200,
            persistImage = { log.add("persistImage"); "stored.jpg" },
        )

        assertEquals(ImageGate.Fits("stored.jpg"), gate)
        assertEquals(listOf("persistImage"), log)
    }

    @Test
    fun gateImageUnderLimitPersistsOnceAndReturnsResult() {
        var persistCalls = 0
        val gate = TurnPersistence.gateImage(
            contentChars = 4,
            maxPayloadBytes = 100,
            persistImage = { persistCalls++; "img" },
        )

        assertEquals(1, persistCalls)
        assertEquals(ImageGate.Fits("img"), gate)
    }

    @Test
    fun gateImageNullPersistResultGivesFitsNull() {
        val gate = TurnPersistence.gateImage<String?>(
            contentChars = 4,
            maxPayloadBytes = 100,
            persistImage = { null },
        )

        assertEquals(ImageGate.Fits(null), gate)
        assertNull((gate as ImageGate.Fits).image)
    }
}
