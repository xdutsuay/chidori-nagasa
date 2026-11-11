package com.xdutsuay.chidori.coordinator.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CP6 — classifyNeedsYouKind category mapping tests. Unknown / null input
 * must fall back to [NeedsYouKind.RUN_APPROVAL] so existing run deeplinks
 * keep working.
 */
class NeedsYouKindTest {

    @Test
    fun pairSummaryClassifiesAsPairing() {
        assertEquals(
            NeedsYouKind.PAIRING,
            classifyNeedsYouKind("Pairing request from DESKTOP-01"),
        )
        // Case-insensitive: "pair" appears in "unpair"/"paired" too, but the
        // pairing bucket is the intended catch-all for pair-ish summaries.
        assertEquals(NeedsYouKind.PAIRING, classifyNeedsYouKind("PAIRED with desktop"))
    }

    @Test
    fun cellularOrMobileDataClassifiesAsCellularInstall() {
        assertEquals(
            NeedsYouKind.CELLULAR_INSTALL,
            classifyNeedsYouKind("Model download needs cellular"),
        )
        assertEquals(
            NeedsYouKind.CELLULAR_INSTALL,
            classifyNeedsYouKind("Waiting for mobile data connection"),
        )
    }

    @Test
    fun nodeOfferClassifiesAsNodeOffer() {
        assertEquals(
            NeedsYouKind.NODE_OFFER,
            classifyNeedsYouKind("Node offer from phone-2"),
        )
    }

    @Test
    fun stopWithRiskClassifiesAsStopAtRisk() {
        assertEquals(
            NeedsYouKind.STOP_AT_RISK,
            classifyNeedsYouKind("Stop requested: risk of losing progress"),
        )
    }

    @Test
    fun stopWithDestructiveClassifiesAsStopAtRisk() {
        assertEquals(
            NeedsYouKind.STOP_AT_RISK,
            classifyNeedsYouKind("Stop now — destructive action pending"),
        )
    }

    @Test
    fun ordinaryRunSummaryClassifiesAsRunApproval() {
        assertEquals(
            NeedsYouKind.RUN_APPROVAL,
            classifyNeedsYouKind("Run needs approval: execute rm -rf build/"),
        )
    }

    @Test
    fun nullSummaryFallsBackToRunApproval() {
        assertEquals(NeedsYouKind.RUN_APPROVAL, classifyNeedsYouKind(null))
    }

    @Test
    fun emptyAndUnrecognizedSummariesFallBackToRunApproval() {
        assertEquals(NeedsYouKind.RUN_APPROVAL, classifyNeedsYouKind(""))
        assertEquals(NeedsYouKind.RUN_APPROVAL, classifyNeedsYouKind("something happened"))
    }
}
