package com.druk.lmplayground.cloud

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.conversation.Message
import com.druk.lmplayground.data.ChatMessageEntity
import com.druk.lmplayground.data.ChatRoute
import com.druk.lmplayground.data.ChatSessionEntity
import com.druk.lmplayground.data.UsageAggregator
import com.druk.lmplayground.data.UsageEventEntity
import com.druk.lmplayground.data.UsageRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class CloudChatViewModel(app: Application) : AndroidViewModel(app) {
    private val providers = (app as App).cloudProviderRepository
    private val client = (app as App).cloudClient
    private val chats = (app as App).chatRepository
    private val usageRepo = (app as App).usageRepository

    private val _provider = MutableLiveData<CloudProviderEntity?>(null)
    val provider: LiveData<CloudProviderEntity?> = _provider

    private val _models = MutableLiveData<List<CloudModelInfo>>(emptyList())
    val models: LiveData<List<CloudModelInfo>> = _models

    private val _selectedModel = MutableLiveData("")
    val selectedModel: LiveData<String> = _selectedModel

    private val _input = MutableLiveData("")
    val input: LiveData<String> = _input

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _awaiting = MutableLiveData(false)
    val awaiting: LiveData<Boolean> = _awaiting

    private val _modelQuery = MutableLiveData("")
    val modelQuery: LiveData<String> = _modelQuery

    val messages: SnapshotStateList<Message> = mutableStateListOf()

    private var sessionId: String? = null
    private var streamJob: Job? = null

    fun setInput(value: String) {
        _input.value = value
    }

    fun setModelQuery(value: String) {
        _modelQuery.value = value
    }

    fun selectModel(id: String) {
        _selectedModel.value = id
        viewModelScope.launch {
            val p = _provider.value ?: return@launch
            providers.saveProvider(p.copy(defaultModel = id), apiKey = null)
            _provider.value = p.copy(defaultModel = id)
        }
    }

    fun loadProvider(providerId: String) {
        viewModelScope.launch {
            val p = providers.getById(providerId)
            _provider.value = p
            if (p == null) {
                _error.value = "Provider not found"
                return@launch
            }
            val key = providers.getKey(providerId)
            if (key.isNullOrBlank()) {
                _error.value = CloudError.NoKey().userMessage()
                return@launch
            }
            _selectedModel.value = p.defaultModel
            client.listModels(p.baseUrl, key).fold(
                onSuccess = { list ->
                    _models.value = list
                    if (_selectedModel.value.isNullOrBlank() && list.isNotEmpty()) {
                        selectModel(list.first().id)
                    }
                },
                onFailure = { e ->
                    _error.value = (e as? CloudClientException)?.error?.userMessage()
                        ?: (e.message ?: "Could not list models")
                },
            )
        }
    }

    fun filteredModels(): List<CloudModelInfo> {
        val q = _modelQuery.value.orEmpty().trim().lowercase()
        val all = _models.value.orEmpty()
        val filtered = if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.id.lowercase().contains(q) || it.name.lowercase().contains(q)
            }
        }
        return filtered.take(50)
    }

    fun send() {
        val text = _input.value.orEmpty().trim()
        val p = _provider.value
        val model = _selectedModel.value.orEmpty()
        if (text.isEmpty() || p == null || model.isBlank() || _awaiting.value == true) return
        val key = providers.getKey(p.id)
        if (key.isNullOrBlank()) {
            _error.value = CloudError.NoKey().userMessage()
            return
        }
        _error.value = null
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            ensureSession(p, model)
            val sid = sessionId ?: return@launch
            val userMsg = Message(
                author = "User",
                content = text,
                timestamp = System.currentTimeMillis(),
            )
            messages.add(userMsg)
            chats.insertMessage(
                ChatMessageEntity(
                    sessionId = sid,
                    author = "User",
                    content = text,
                    timestamp = System.currentTimeMillis(),
                ),
            )
            val assistant = Message(
                author = "Assistant",
                content = "",
                timestamp = System.currentTimeMillis(),
            )
            messages.add(assistant)
            val assistantIndex = messages.lastIndex
            _awaiting.value = true
            val history = messages.dropLast(1).map {
                CloudChatMessage(
                    role = if (it.author == "User") "user" else "assistant",
                    content = it.content,
                )
            }
            var usage: CloudUsage? = null
            var failedAuth = false
            try {
                client.streamChat(p.baseUrl, key, model, history).collect { event ->
                    when (event) {
                        is CloudStreamEvent.Delta -> {
                            val cur = messages[assistantIndex]
                            messages[assistantIndex] = cur.copy(content = cur.content + event.text)
                        }
                        is CloudStreamEvent.Finished -> usage = event.usage
                    }
                }
                _input.value = ""
                val finalText = messages[assistantIndex].content
                chats.insertMessage(
                    ChatMessageEntity(
                        sessionId = sid,
                        author = "Assistant",
                        content = finalText,
                        responseTokens = usage?.completionTokens ?: 0,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
                chats.updateSessionTimestamp(sid, System.currentTimeMillis())
                usageRepo.record(
                    UsageEventEntity(
                        createdAt = System.currentTimeMillis(),
                        route = UsageRoute.CLOUD,
                        provider = p.name,
                        model = model,
                        promptTokens = usage?.promptTokens,
                        completionTokens = usage?.completionTokens,
                        costMicros = usage?.costUsd?.let { UsageAggregator.usdToMicros(it) },
                        estimated = false,
                        sessionId = sid,
                    ),
                )
            } catch (e: CloudClientException) {
                _error.value = e.error.userMessage()
                failedAuth = e.error is CloudError.Unauthorized
                if (!failedAuth) {
                    _input.value = ""
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Stream failed"
                _input.value = ""
            } finally {
                _awaiting.value = false
            }
        }
    }

    private suspend fun ensureSession(p: CloudProviderEntity, model: String) {
        if (sessionId != null) return
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        chats.insertSession(
            ChatSessionEntity(
                id = id,
                title = model,
                modelFilename = model,
                modelName = "${p.name} · $model",
                createdAt = now,
                updatedAt = now,
                route = ChatRoute.CLOUD,
                cloudProviderId = p.id,
                cloudModel = model,
            ),
        )
        sessionId = id
    }
}
