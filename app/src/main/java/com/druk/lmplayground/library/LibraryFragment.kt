package com.druk.lmplayground.library

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.druk.lmplayground.R
import com.druk.lmplayground.theme.PlaygroundTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel over the process-wide Library singletons. Sources are refreshed on
 * start, after each user action, and whenever the indexer reports Done/Failed.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val store = Library.store(appContext)
    private val indexer = Library.indexer(appContext)

    private val _sources = MutableStateFlow<List<LibrarySource>>(emptyList())
    val sources: StateFlow<List<LibrarySource>> = _sources

    val indexState: StateFlow<IndexState> = indexer.state

    init {
        refresh()
        // Re-list after indexing finishes (or fails) so counts reflect the
        // new/changed documents without an extra user action.
        viewModelScope.launch {
            indexer.state.collect { state ->
                if (state is IndexState.Done || state is IndexState.Failed) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { store.sources() }
                .onSuccess { _sources.value = it }
        }
    }

    fun addAndIndex(uri: Uri, isFolder: Boolean) {
        viewModelScope.launch {
            runCatching { indexer.addAndIndex(uri, isFolder) }
            refresh()
        }
    }

    fun removeSource(sourceId: Long) {
        viewModelScope.launch {
            runCatching { indexer.remove(sourceId) }
                .onFailure { notifyRemoveFailed(sourceId, it) }
            refresh()
        }
    }

    fun reindexAll() {
        viewModelScope.launch {
            runCatching { indexer.reindexAll() }
            refresh()
        }
    }

    private fun notifyRemoveFailed(sourceId: Long, error: Throwable) {
        val name = _sources.value.firstOrNull { it.id == sourceId }?.displayName ?: return
        Toast.makeText(
            appContext,
            appContext.getString(R.string.library_remove_failed, name),
            Toast.LENGTH_LONG,
        ).show()
    }
}

class LibraryFragment : Fragment() {

    private val viewModel: LibraryViewModel by viewModels()

    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) viewModel.addAndIndex(uri, isFolder = false)
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) viewModel.addAndIndex(uri, isFolder = true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(inflater.context).apply {
        layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
        setContent {
            PlaygroundTheme {
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val indexState by viewModel.indexState.collectAsStateWithLifecycle()
                LibraryScreen(
                    sources = sources,
                    indexState = indexState,
                    onBack = { findNavController().popBackStack() },
                    onAddFile = { pickDocument(isFolder = false) },
                    onAddFolder = { pickDocument(isFolder = true) },
                    onRemove = { source -> viewModel.removeSource(source.id) },
                    onReindexAll = viewModel::reindexAll,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun pickDocument(isFolder: Boolean) {
        try {
            if (isFolder) {
                openFolderLauncher.launch(null)
            } else {
                openFileLauncher.launch(arrayOf("*/*"))
            }
        } catch (_: ActivityNotFoundException) {
            // Some devices ship without (or with disabled) DocumentsUI and
            // ActivityResultLauncher throws synchronously (see MainActivity).
            Toast.makeText(
                requireContext(),
                R.string.library_picker_unavailable,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
