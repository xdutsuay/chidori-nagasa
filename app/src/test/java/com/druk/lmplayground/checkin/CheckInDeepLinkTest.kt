package com.druk.lmplayground.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckInDeepLinkTest {
    @Test
    fun parsesACompleteLink() {
        val link = parseCheckInDeepLink(
            action = ACTION_OPEN_CHECK_IN,
            source = "desktop_run",
            sourceId = "run-1",
            sessionId = null,
            instanceId = "desk",
            runId = "run-1",
        )
        assertEquals("desktop_run", link?.source)
        assertEquals("desk", link?.instanceId)
        assertEquals("run-1", link?.runId)
    }

    @Test
    fun rejectsOtherActionsAndBlankIds() {
        assertNull(parseCheckInDeepLink("android.intent.action.MAIN", "chat", "s", null, null, null))
        assertNull(parseCheckInDeepLink(ACTION_OPEN_CHECK_IN, "chat", "  ", "s", null, null))
        assertNull(parseCheckInDeepLink(ACTION_OPEN_CHECK_IN, "", "s", null, null, null))
    }
}
