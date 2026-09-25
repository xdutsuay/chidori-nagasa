package com.druk.lmplayground.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.App
import com.druk.lmplayground.R
import kotlinx.coroutines.launch
import java.util.UUID

class CloudApisViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).cloudProviderRepository
    private val client = (app as App).cloudClient

    val providers: LiveData<List<CloudProviderEntity>> = repo.observeAll()

    private val _status = MutableLiveData<String?>(null)
    val status: LiveData<String?> = _status

    private val _testing = MutableLiveData(false)
    val testing: LiveData<Boolean> = _testing

    fun clearStatus() {
        _status.value = null
    }

    fun save(
        presetId: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
        allowHttp: Boolean,
        existingId: String? = null,
    ) {
        val error = CloudPresets.validateBaseUrl(baseUrl, allowHttp)
        if (error != null) {
            _status.value = error
            return
        }
        if (apiKey.isBlank() && (existingId == null || !repo.hasKey(existingId))) {
            _status.value = getApplication<Application>().getString(R.string.cloud_apis_status_paste_key)
            return
        }
        viewModelScope.launch {
            try {
                val id = existingId ?: (presetId ?: UUID.randomUUID().toString())
                val entity = CloudProviderEntity(
                    id = id,
                    name = name.trim().ifBlank { "Custom" },
                    baseUrl = baseUrl.trim().trimEnd('/'),
                    defaultModel = repo.getById(id)?.defaultModel.orEmpty(),
                    allowHttp = allowHttp,
                    createdAt = repo.getById(id)?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                repo.saveProvider(entity, apiKey.takeIf { it.isNotBlank() })
                _status.value = getApplication<Application>().getString(R.string.cloud_apis_status_saved)
            } catch (e: CloudKeyStoreException) {
                _status.value = e.message
            } catch (e: Exception) {
                _status.value = e.message
                    ?: getApplication<Application>().getString(R.string.cloud_apis_status_save_failed)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.deleteProvider(id)
            _status.value = getApplication<Application>().getString(R.string.cloud_apis_status_deleted)
        }
    }

    fun testConnection(id: String) {
        viewModelScope.launch {
            _testing.value = true
            try {
                val provider = repo.getById(id)
                if (provider == null) {
                    _status.value = getApplication<Application>()
                        .getString(R.string.cloud_apis_status_not_found)
                    return@launch
                }
                val key = repo.getKey(id)
                if (key.isNullOrBlank()) {
                    _status.value = CloudError.NoKey().userMessage()
                    return@launch
                }
                val result = client.listModels(provider.baseUrl, key)
                result.fold(
                    onSuccess = { models ->
                        _status.value = getApplication<Application>().getString(
                            R.string.cloud_apis_status_test_ok,
                            models.size,
                        )
                        if (provider.defaultModel.isBlank() && models.isNotEmpty()) {
                            repo.saveProvider(
                                provider.copy(defaultModel = models.first().id),
                                apiKey = null,
                            )
                        }
                    },
                    onFailure = { e ->
                        val msg = (e as? CloudClientException)?.error?.userMessage()
                            ?: (e.message
                                ?: getApplication<Application>()
                                    .getString(R.string.cloud_apis_status_test_failed))
                        _status.value = msg
                    },
                )
            } finally {
                _testing.value = false
            }
        }
    }

    fun hasKey(id: String): Boolean = repo.hasKey(id)
}
