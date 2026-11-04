package com.druk.lmplayground.conversation

import com.druk.lmplayground.data.ChatRoute
import com.druk.lmplayground.data.ChatSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hub drawer session filtering (A+B+C product slice) — TEST AUTHOR RED.
 * This file does not compile until the implementer lands the surface below.
 *
 * == IMPLEMENTER CONTRACT (make these green, do not weaken assertions) ==
 *
 * The hub drawer needs route chips (All / On-device / Cloud) plus a
 * needs-you badge count on the destination row.
 *
 * Surface to implement in `com.druk.lmplayground.conversation`:
 *
 *   enum class HubSessionFilter { ALL, ON_DEVICE, CLOUD }
 *
 *   /** Filter chat sessions for the hub drawer route chips.
 *    *  ALL → unchanged order; ON_DEVICE → route == ChatRoute.LOCAL;
 *    *  CLOUD → route == ChatRoute.CLOUD.
 *    *  Unknown/blank route treated as LOCAL (legacy rows).
 *    */
 *   fun filterSessionsForHub(
 *       sessions: List<ChatSessionEntity>,
 *       filter: HubSessionFilter,
 *   ): List<ChatSessionEntity>
 *
 *   /** Format a needs_you badge count for the drawer destination row.
 *    *  0 → null (no badge); 1..99 → that number as string; 100+ → "99+".
 *    */
 *   fun formatNeedsYouBadge(count: Int): String?
 *
 * Notes for the implementer:
 *  - Both are pure JVM functions over the data classes; no Android or
 *    Robolectric dependencies required.
 *  - "Blank/legacy" route means empty or whitespace-only (Kotlin `isBlank`)
 *    or any string that is neither ChatRoute.LOCAL nor ChatRoute.CLOUD —
 *    such rows must appear under ON_DEVICE, never under CLOUD.
 *  - ALL must preserve input order and never mutate or deduplicate.
 */
class HubSessionFilterTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun session(
        id: String,
        route: String,
    ): ChatSessionEntity = ChatSessionEntity(
        id = id,
        title = "Session $id",
        modelFilename = "",
        modelName = "test-model",
        createdAt = 1L,
        updatedAt = 1L,
        route = route,
    )

    private val localA = session("local-a", ChatRoute.LOCAL)
    private val localB = session("local-b", ChatRoute.LOCAL)
    private val cloudA = session("cloud-a", ChatRoute.CLOUD)
    private val cloudB = session("cloud-b", ChatRoute.CLOUD)
    private val blankRoute = session("blank", "") // legacy row
    private val unknownRoute = session("unknown", "SOMEWHERE_ELSE")

    private val mixed = listOf(localA, cloudA, blankRoute, cloudB, localB, unknownRoute)

    // ------------------------------------------------------------------
    // filterSessionsForHub
    // ------------------------------------------------------------------

    @Test
    fun `ALL returns every session in the same order`() {
        val result = filterSessionsForHub(mixed, HubSessionFilter.ALL)

        assertEquals(
            listOf("local-a", "cloud-a", "blank", "cloud-b", "local-b", "unknown"),
            result.map { it.id },
        )
    }

    @Test
    fun `ON_DEVICE keeps LOCAL and blank or unknown legacy rows and drops CLOUD`() {
        val result = filterSessionsForHub(mixed, HubSessionFilter.ON_DEVICE)

        assertEquals(
            listOf("local-a", "blank", "local-b", "unknown"),
            result.map { it.id },
        )
    }

    @Test
    fun `CLOUD keeps only CLOUD sessions in order`() {
        val result = filterSessionsForHub(mixed, HubSessionFilter.CLOUD)

        assertEquals(
            listOf("cloud-a", "cloud-b"),
            result.map { it.id },
        )
    }

    @Test
    fun `CLOUD filter over empty list returns empty list`() {
        assertEquals(emptyList<ChatSessionEntity>(), filterSessionsForHub(emptyList(), HubSessionFilter.CLOUD))
    }

    @Test
    fun `ON_DEVICE filter over empty list returns empty list`() {
        assertEquals(emptyList<ChatSessionEntity>(), filterSessionsForHub(emptyList(), HubSessionFilter.ON_DEVICE))
    }

    // ------------------------------------------------------------------
    // formatNeedsYouBadge
    // ------------------------------------------------------------------

    @Test
    fun `badge count zero means no badge`() {
        assertNull(formatNeedsYouBadge(0))
    }

    @Test
    fun `badge count three renders as three`() {
        assertEquals("3", formatNeedsYouBadge(3))
    }

    @Test
    fun `badge count ninety-nine renders as ninety-nine`() {
        assertEquals("99", formatNeedsYouBadge(99))
    }

    @Test
    fun `badge count one hundred caps at ninety-nine plus`() {
        assertEquals("99+", formatNeedsYouBadge(100))
    }

    @Test
    fun `badge count far beyond cap still renders ninety-nine plus`() {
        assertEquals("99+", formatNeedsYouBadge(12345))
    }

    @Test
    fun `negative badge count means no badge`() {
        assertNull(formatNeedsYouBadge(-1))
    }

    // ------------------------------------------------------------------
    // DESKTOP chip + title search + usage footer
    // ------------------------------------------------------------------

    @Test
    fun `DESKTOP filter always returns empty list`() {
        assertEquals(emptyList<ChatSessionEntity>(), filterSessionsForHub(mixed, HubSessionFilter.DESKTOP))
        assertEquals(
            emptyList<ChatSessionEntity>(),
            filterSessionsForHub(mixed, HubSessionFilter.DESKTOP, query = "local"),
        )
    }

    @Test
    fun `title search is case insensitive within ALL`() {
        val titled = listOf(
            localA.copy(title = "Alpha Plan"),
            cloudA.copy(title = "beta notes"),
            localB.copy(title = "Gamma"),
        )
        val result = filterSessionsForHub(titled, HubSessionFilter.ALL, query = "BETA")
        assertEquals(listOf("cloud-a"), result.map { it.id })
    }

    @Test
    fun `blank search leaves filtered list unchanged`() {
        val result = filterSessionsForHub(mixed, HubSessionFilter.CLOUD, query = "   ")
        assertEquals(listOf("cloud-a", "cloud-b"), result.map { it.id })
    }

    @Test
    fun `search narrows ON_DEVICE results`() {
        val titled = listOf(
            localA.copy(title = "Kitchen sink"),
            blankRoute.copy(title = "Elsewhere"),
            localB.copy(title = "Kitchen timer"),
        )
        val result = filterSessionsForHub(titled, HubSessionFilter.ON_DEVICE, query = "kitchen")
        assertEquals(listOf("local-a", "local-b"), result.map { it.id })
    }

    @Test
    fun `usage footer with zero requests`() {
        assertEquals("No usage today", formatUsageDrawerFooter(0, 0))
        assertEquals("No usage today", formatUsageDrawerFooter(-3, 999))
    }

    @Test
    fun `usage footer formats compact token counts`() {
        assertEquals("2 requests · 500 tokens", formatUsageDrawerFooter(2, 500))
        assertEquals("1 requests · 1.5k tokens", formatUsageDrawerFooter(1, 1_500))
        assertEquals("4 requests · 2.1M tokens", formatUsageDrawerFooter(4, 2_100_000))
    }
}
