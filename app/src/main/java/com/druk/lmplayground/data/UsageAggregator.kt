package com.druk.lmplayground.data

/**
 * Local aggregates over [UsageEventEntity] rows. Cost sums skip nulls.
 */
object UsageAggregator {
    data class Totals(
        val requests: Int = 0,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val costMicros: Long? = null,
        val costKnownCount: Int = 0,
    )

    data class DayBucket(
        val dayStartMs: Long,
        val requests: Int,
    )

    data class BreakdownRow(
        val key: String,
        val requests: Int,
        val promptTokens: Long,
        val completionTokens: Long,
        val costMicros: Long?,
    )

    fun totals(events: List<UsageEventEntity>): Totals {
        var requests = 0
        var prompt = 0L
        var completion = 0L
        var costSum = 0L
        var costKnown = 0
        for (e in events) {
            requests++
            prompt += e.promptTokens?.toLong() ?: 0L
            completion += e.completionTokens?.toLong() ?: 0L
            e.costMicros?.let {
                costSum += it
                costKnown++
            }
        }
        return Totals(
            requests = requests,
            promptTokens = prompt,
            completionTokens = completion,
            costMicros = if (costKnown > 0) costSum else null,
            costKnownCount = costKnown,
        )
    }

    fun byRoute(events: List<UsageEventEntity>): List<BreakdownRow> =
        group(events) { it.route.ifBlank { "?" } }

    fun byModel(events: List<UsageEventEntity>): List<BreakdownRow> =
        group(events) { it.model.ifBlank { "(unknown)" } }

    /** One bucket per UTC day containing [sinceMs]..now, including empty days. */
    fun requestsPerDay(events: List<UsageEventEntity>, sinceMs: Long, nowMs: Long): List<DayBucket> {
        val dayMs = 86_400_000L
        val startDay = (sinceMs / dayMs) * dayMs
        val endDay = (nowMs / dayMs) * dayMs
        val counts = mutableMapOf<Long, Int>()
        var d = startDay
        while (d <= endDay) {
            counts[d] = 0
            d += dayMs
        }
        for (e in events) {
            val key = (e.createdAt / dayMs) * dayMs
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.entries.sortedBy { it.key }.map { DayBucket(it.key, it.value) }
    }

    private fun group(
        events: List<UsageEventEntity>,
        keyOf: (UsageEventEntity) -> String,
    ): List<BreakdownRow> {
        data class Acc(
            var requests: Int = 0,
            var prompt: Long = 0,
            var completion: Long = 0,
            var cost: Long = 0,
            var costKnown: Int = 0,
        )
        val map = linkedMapOf<String, Acc>()
        for (e in events) {
            val acc = map.getOrPut(keyOf(e)) { Acc() }
            acc.requests++
            acc.prompt += e.promptTokens?.toLong() ?: 0L
            acc.completion += e.completionTokens?.toLong() ?: 0L
            e.costMicros?.let {
                acc.cost += it
                acc.costKnown++
            }
        }
        return map.entries
            .map { (k, a) ->
                BreakdownRow(
                    key = k,
                    requests = a.requests,
                    promptTokens = a.prompt,
                    completionTokens = a.completion,
                    costMicros = if (a.costKnown > 0) a.cost else null,
                )
            }
            .sortedByDescending { it.requests }
    }

    fun usdToMicros(costUsd: Double): Long =
        kotlin.math.round(costUsd * 1_000_000.0).toLong()
}
