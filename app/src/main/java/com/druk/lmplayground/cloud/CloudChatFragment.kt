package com.druk.lmplayground.cloud

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.theme.PlaygroundTheme

class CloudChatFragment : Fragment() {
    private val viewModel: CloudChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = arguments?.getString(ARG_PROVIDER_ID)
        if (id != null) viewModel.loadProvider(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val provider by viewModel.provider.observeAsState()
                val selectedModel by viewModel.selectedModel.observeAsState("")
                val modelQuery by viewModel.modelQuery.observeAsState("")
                val models by viewModel.models.observeAsState(emptyList())
                val input by viewModel.input.observeAsState("")
                val awaiting by viewModel.awaiting.observeAsState(false)
                val error by viewModel.error.observeAsState()
                val filtered = remember(models, modelQuery) { viewModel.filteredModels() }
                CloudChatScreen(
                    providerName = provider?.name.orEmpty(),
                    selectedModel = selectedModel.orEmpty(),
                    modelQuery = modelQuery.orEmpty(),
                    models = filtered,
                    messages = viewModel.messages,
                    input = input.orEmpty(),
                    awaiting = awaiting == true,
                    error = error,
                    onBackClick = { findNavController().popBackStack() },
                    onModelQueryChange = { viewModel.setModelQuery(it) },
                    onSelectModel = { viewModel.selectModel(it) },
                    onInputChange = { viewModel.setInput(it) },
                    onSend = { viewModel.send() },
                )
            }
        }
    }

    companion object {
        const val ARG_PROVIDER_ID = "provider_id"
    }
}
