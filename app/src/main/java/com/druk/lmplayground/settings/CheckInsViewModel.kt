package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.checkin.BackgroundActivityPause
import com.druk.lmplayground.checkin.CheckInFrequency
import com.druk.lmplayground.checkin.CheckInPreferences
import com.druk.lmplayground.checkin.CheckInScheduler
import com.druk.lmplayground.checkin.CheckInUserAction
import com.druk.lmplayground.checkin.OpenItemEntity
import com.druk.lmplayground.checkin.OpenItemStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Check-ins. Writes [CheckInPreferences] and the app-wide
 * [BackgroundActivityPause], and reschedules the unique check-in work when
 * the toggle or the frequency changes.
 */
class CheckInsViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences = CheckInPreferences(app)
    private val pause = BackgroundActivityPause(app)
    private val repository = (app as App).openItemRepository

    private val _enabled = MutableStateFlow(preferences.enabled)
    val enabled: StateFlow<Boolean> = _enabled

    private val _frequency = MutableStateFlow(preferences.frequency)
    val frequency: StateFlow<CheckInFrequency> = _frequency

    private val _inactivity = MutableStateFlow(preferences.inactivityThresholdMs)
    val inactivityMs: StateFlow<Long> = _inactivity

    private val _quietStart = MutableStateFlow(preferences.quietStartMinute)
    val quietStartMinute: StateFlow<Int> = _quietStart

    private val _quietEnd = MutableStateFlow(preferences.quietEndMinute)
    val quietEndMinute: StateFlow<Int> = _quietEnd

    private val _paused = MutableStateFlow(pause.isPaused)
    val paused: StateFlow<Boolean> = _paused

    val items: StateFlow<List<OpenItemEntity>> = repository.observeAll()
        .map { rows ->
            rows.filter {
                it.status != OpenItemStatus.DONE.name && it.status != OpenItemStatus.CLOSED.name
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(value: Boolean) {
        preferences.enabled = value
        _enabled.value = value
        CheckInScheduler.sync(getApplication())
    }

    fun setFrequency(value: CheckInFrequency) {
        preferences.frequency = value
        _frequency.value = value
        CheckInScheduler.sync(getApplication())
    }

    fun setInactivity(value: Long) {
        preferences.inactivityThresholdMs = value
        _inactivity.value = value
    }

    fun setQuietStart(value: Int) {
        preferences.quietStartMinute = value
        _quietStart.value = preferences.quietStartMinute
    }

    fun setQuietEnd(value: Int) {
        preferences.quietEndMinute = value
        _quietEnd.value = preferences.quietEndMinute
    }

    fun setPaused(value: Boolean) {
        pause.isPaused = value
        _paused.value = value
    }

    fun park(item: OpenItemEntity) = act(item, CheckInUserAction.PARK)

    fun close(item: OpenItemEntity) = act(item, CheckInUserAction.CLOSE)

    private fun act(item: OpenItemEntity, action: CheckInUserAction) {
        viewModelScope.launch {
            repository.applyUserAction(listOf(item.id), action, System.currentTimeMillis())
        }
    }
}
