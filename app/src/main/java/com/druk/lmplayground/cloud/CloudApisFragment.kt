package com.druk.lmplayground.cloud

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme

class CloudApisFragment : Fragment() {
    private val viewModel: CloudApisViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val providers by viewModel.providers.observeAsState(emptyList())
                val status by viewModel.status.observeAsState()
                val testing by viewModel.testing.observeAsState(false)
                CloudApisScreen(
                    providers = providers,
                    status = status,
                    testing = testing == true,
                    hasKey = { viewModel.hasKey(it) },
                    onBackClick = { findNavController().popBackStack() },
                    onSave = { presetId, name, baseUrl, apiKey, allowHttp, existingId ->
                        viewModel.save(presetId, name, baseUrl, apiKey, allowHttp, existingId)
                    },
                    onDelete = { viewModel.delete(it) },
                    onTest = { viewModel.testConnection(it) },
                    onOpenChat = { id ->
                        if (findNavController().currentDestination?.id == R.id.nav_cloud_apis) {
                            findNavController().navigate(
                                R.id.action_cloud_apis_to_cloud_chat,
                                bundleOf(CloudChatFragment.ARG_PROVIDER_ID to id),
                            )
                        }
                    },
                    onClearStatus = { viewModel.clearStatus() },
                )
            }
        }
    }
}
