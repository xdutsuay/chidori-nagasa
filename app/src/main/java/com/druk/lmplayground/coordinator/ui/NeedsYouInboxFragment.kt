package com.druk.lmplayground.coordinator.ui

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
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

/**
 * Hub "Needs you" inbox — lists blocked desktop runs and deep-links into
 * Chidori Desktop monitor for approve/reject.
 */
class NeedsYouInboxFragment : Fragment() {

    private val viewModel: NeedsYouInboxViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val items by viewModel.items.collectAsStateWithLifecycle()
                val loading by viewModel.loading.collectAsStateWithLifecycle()
                val status by viewModel.statusMessage.collectAsStateWithLifecycle()
                NeedsYouInboxScreen(
                    items = items,
                    loading = loading,
                    statusMessage = status,
                    onBack = { findNavController().popBackStack() },
                    onItemClick = { item ->
                        viewModel.openItem(item)
                        if (findNavController().currentDestination?.id == R.id.nav_needs_you) {
                            findNavController().navigate(R.id.action_needs_you_to_chidori)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
