package com.druk.lmplayground.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.theme.PlaygroundTheme

/** Phone destination for Settings → Check-ins. */
class CheckInsFragment : Fragment() {
    private val viewModel: CheckInsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val enabled by viewModel.enabled.collectAsStateWithLifecycle()
                val frequency by viewModel.frequency.collectAsStateWithLifecycle()
                val inactivity by viewModel.inactivityMs.collectAsStateWithLifecycle()
                val quietStart by viewModel.quietStartMinute.collectAsStateWithLifecycle()
                val quietEnd by viewModel.quietEndMinute.collectAsStateWithLifecycle()
                val paused by viewModel.paused.collectAsStateWithLifecycle()
                val items by viewModel.items.collectAsStateWithLifecycle()
                CheckInsScreen(
                    enabled = enabled,
                    frequency = frequency,
                    inactivityMs = inactivity,
                    quietStartMinute = quietStart,
                    quietEndMinute = quietEnd,
                    paused = paused,
                    items = items,
                    onEnabled = viewModel::setEnabled,
                    onFrequency = viewModel::setFrequency,
                    onInactivity = viewModel::setInactivity,
                    onQuietStart = viewModel::setQuietStart,
                    onQuietEnd = viewModel::setQuietEnd,
                    onPaused = viewModel::setPaused,
                    onPark = viewModel::park,
                    onClose = viewModel::close,
                    onBackClick = { findNavController().popBackStack() },
                )
            }
        }
    }
}
