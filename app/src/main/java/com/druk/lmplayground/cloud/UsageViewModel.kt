package com.druk.lmplayground.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.data.UsageAggregator
import com.druk.lmplayground.data.UsageEventEntity
import kotlinx.coroutines.launch

enum class UsageRange(val label: String, val windowMs: Long) {
    H24("24h", 24L * 60 * 60 * 1000),
    D7("7d", 7L * 24 * 60 * 60 * 1000),
    D30("30d", 30L * 24 * 60 * 60 * 1000),
}

data class UsageUiState(
    val range: UsageRange = UsageRange.D7,
    val totals: UsageAggregator.Totals = UsageAggregator.Totals(),
    val perDay: List<UsageAggregator.DayBucket> = emptyList(),
    val byRoute: List<UsageAggregator.BreakdownRow> = emptyList(),
    val byModel: List<UsageAggregator.BreakdownRow> = emptyList(),
)

class UsageViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).usageRepository

    private val _state = MutableLiveData(UsageUiState())
    val state: LiveData<UsageUiState> = _state

    init {
        load(UsageRange.D7)
    }

    fun load(range: UsageRange) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val since = now - range.windowMs
            val events: List<UsageEventEntity> = repo.since(since)
            _state.value = UsageUiState(
                range = range,
                totals = UsageAggregator.totals(events),
                perDay = UsageAggregator.requestsPerDay(events, since, now),
                byRoute = UsageAggregator.byRoute(events),
                byModel = UsageAggregator.byModel(events),
            )
        }
    }
}
