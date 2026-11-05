package com.druk.lmplayground.hybrid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CP10 tests for the hybrid composer router chip model.
 *
 * Production symbols exercised (package com.druk.lmplayground.hybrid):
 *  - enum TurnRoute { LOCAL, DESKTOP, API }
 *  - TurnRoute.storageValue(): String (lowercase name, e.g. "desktop")
 *  - TurnRoute.fromStorage(raw: String?): TurnRoute
 *      (accepts storage strings AND chip labels: "local", "desktop", "api",
 *       alias "cloud"; case-insensitive; trims whitespace; null/unknown -> LOCAL)
 *  - object HybridRouter.resolve(
 *        selected: TurnRoute, desktopPaired: Boolean, apiConfigured: Boolean
 *    ): TurnRoute
 *      (DESKTOP falls back to LOCAL when unpaired; API falls back to LOCAL
 *       when unconfigured; LOCAL always stays LOCAL)
 */
class TurnRouteTest {

    // ---- enum shape ----

    @Test
    fun `enum has exactly local desktop api`() {
        assertEquals(listOf(TurnRoute.LOCAL, TurnRoute.DESKTOP, TurnRoute.API), TurnRoute.entries)
    }

    // ---- storageValue ----

    @Test
    fun `storageValue is lowercase name`() {
        assertEquals("local", TurnRoute.LOCAL.storageValue())
        assertEquals("desktop", TurnRoute.DESKTOP.storageValue())
        assertEquals("api", TurnRoute.API.storageValue())
    }

    // ---- fromStorage: storage strings ----

    @Test
    fun `fromStorage parses lowercase storage strings`() {
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage("local"))
        assertEquals(TurnRoute.DESKTOP, TurnRoute.fromStorage("desktop"))
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("api"))
    }

    @Test
    fun `fromStorage is case insensitive`() {
        assertEquals(TurnRoute.DESKTOP, TurnRoute.fromStorage("DESKTOP"))
        assertEquals(TurnRoute.DESKTOP, TurnRoute.fromStorage("Desktop"))
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("API"))
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("Api"))
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage("Local"))
    }

    @Test
    fun `fromStorage trims surrounding whitespace`() {
        assertEquals(TurnRoute.DESKTOP, TurnRoute.fromStorage("  desktop  "))
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("\tapi\n"))
    }

    @Test
    fun `fromStorage accepts cloud alias for api`() {
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("cloud"))
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("Cloud"))
    }

    @Test
    fun `fromStorage null returns local`() {
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage(null))
    }

    @Test
    fun `fromStorage empty and blank strings return local`() {
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage(""))
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage("   "))
    }

    @Test
    fun `fromStorage unknown string falls back to local`() {
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage("quantum"))
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage("server"))
    }

    // ---- fromStorage: chip labels round-trip through the same parser ----

    @Test
    fun `fromStorage parses chip display labels`() {
        assertEquals(TurnRoute.LOCAL, TurnRoute.fromStorage("Local"))
        assertEquals(TurnRoute.DESKTOP, TurnRoute.fromStorage("Desktop"))
        assertEquals(TurnRoute.API, TurnRoute.fromStorage("API"))
    }

    @Test
    fun `storageValue and fromStorage round trip for all routes`() {
        for (route in TurnRoute.entries) {
            assertEquals(route, TurnRoute.fromStorage(route.storageValue()))
        }
    }

    // ---- HybridRouter.resolve ----

    @Test
    fun `resolve keeps local regardless of pairing or api config`() {
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.LOCAL, desktopPaired = false, apiConfigured = false))
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.LOCAL, desktopPaired = true, apiConfigured = false))
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.LOCAL, desktopPaired = false, apiConfigured = true))
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.LOCAL, desktopPaired = true, apiConfigured = true))
    }

    @Test
    fun `resolve keeps desktop when desktop is paired`() {
        assertEquals(TurnRoute.DESKTOP, HybridRouter.resolve(TurnRoute.DESKTOP, desktopPaired = true, apiConfigured = false))
        assertEquals(TurnRoute.DESKTOP, HybridRouter.resolve(TurnRoute.DESKTOP, desktopPaired = true, apiConfigured = true))
    }

    @Test
    fun `resolve falls back to local when desktop selected but unpaired`() {
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.DESKTOP, desktopPaired = false, apiConfigured = false))
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.DESKTOP, desktopPaired = false, apiConfigured = true))
    }

    @Test
    fun `resolve keeps api when api is configured`() {
        assertEquals(TurnRoute.API, HybridRouter.resolve(TurnRoute.API, desktopPaired = false, apiConfigured = true))
        assertEquals(TurnRoute.API, HybridRouter.resolve(TurnRoute.API, desktopPaired = true, apiConfigured = true))
    }

    @Test
    fun `resolve falls back to local when api selected but not configured`() {
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.API, desktopPaired = false, apiConfigured = false))
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.API, desktopPaired = true, apiConfigured = false))
    }

    @Test
    fun `resolve never escalates local to desktop or api`() {
        // Even with everything available, an explicit LOCAL selection stays LOCAL.
        assertEquals(TurnRoute.LOCAL, HybridRouter.resolve(TurnRoute.LOCAL, desktopPaired = true, apiConfigured = true))
    }
}
