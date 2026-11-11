package com.xdutsuay.chidori.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.xdutsuay.chidori.theme.PlaygroundTheme

class ToolsFragment : Fragment() {

    private val toolsViewModel: ToolsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val enabled by toolsViewModel.enabled.collectAsStateWithLifecycle()
                val confirmMode by toolsViewModel.confirmMode.collectAsStateWithLifecycle()
                val evidenceDogfood by toolsViewModel.evidenceDogfood.collectAsStateWithLifecycle()
                ToolsScreen(
                    tools = toolsViewModel.tools,
                    enabledStates = enabled,
                    onToolEnabledChanged = { name, value -> toolsViewModel.setEnabled(name, value) },
                    onBackClick = { findNavController().popBackStack() },
                    confirmMode = confirmMode,
                    onConfirmModeChanged = toolsViewModel::setConfirmMode,
                    evidenceDogfood = evidenceDogfood,
                    onEvidenceDogfoodChanged = toolsViewModel::setEvidenceDogfood,
                )
            }
        }
    }
}
