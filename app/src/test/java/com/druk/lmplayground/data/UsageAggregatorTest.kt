package com.druk.lmplayground.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAggregatorTest {

    @Test
    fun `totals sum tokens and known costs only`() {
        val events = listOf(
            UsageEventEntity(
                createdAt = 1,
                route = UsageRoute.CLOUD,
                model = "a",
                promptTokens = 10,
                completionTokens = 5,
                costMicros = 1_000,
            ),
            UsageEventEntity(
                createdAt = 2,
                route = UsageRoute.LOCAL,
                model = "b",
                promptTokens = 3,
                completionTokens = 7,
                costMicros = null,
            ),
        )
        val totals = UsageAggregator.totals(events)
        assertEquals(2, totals.requests)
        assertEquals(13L, totals.promptTokens)
        assertEquals(12L, totals.completionTokens)
        assertEquals(1_000L, totals.costMicros)
        assertEquals(1, totals.costKnownCount)
    }

    @Test
    fun `null cost stays null when nothing reported`() {
        val events = listOf(
            UsageEventEntity(createdAt = 1, route = UsageRoute.LOCAL, model = "m"),
        )
        assertNull(UsageAggregator.totals(events).costMicros)
    }

    @Test
    fun `byRoute groups requests`() {
        val events = listOf(
            UsageEventEntity(createdAt = 1, route = UsageRoute.CLOUD, model = "a"),
            UsageEventEntity(createdAt = 2, route = UsageRoute.CLOUD, model = "b"),
            UsageEventEntity(createdAt = 3, route = UsageRoute.LOCAL, model = "c"),
        )
        val rows = UsageAggregator.byRoute(events)
        assertEquals(2, rows.first { it.key == UsageRoute.CLOUD }.requests)
        assertEquals(1, rows.first { it.key == UsageRoute.LOCAL }.requests)
    }

    @Test
    fun `usdToMicros rounds`() {
        assertEquals(1_200L, UsageAggregator.usdToMicros(0.0012))
    }
}
