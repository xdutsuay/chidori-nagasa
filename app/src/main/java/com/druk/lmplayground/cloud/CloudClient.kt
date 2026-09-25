package com.druk.lmplayground.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible HTTPS client for cloud chat.
 * Does not use OkHttp BODY logging — keys and prompts must stay out of logcat.
 */
class CloudClient(
    private val http: OkHttpClient = defaultClient(),
    private val appReferer: String = "https://github.com/xdutsuay/chidori-nagasa",
    private val appTitle: String = "chidori-nagasa",
) {
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<CloudModelInfo>> =
        withContext(Dispatchers.IO) {
            try {
                val request = authedGet(CloudEndpoints.modelsUrl(baseUrl), apiKey)
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            CloudClientException(CloudSseParser.mapHttpCode(response.code, body)),
                        )
                    }
                    val root = JSONObject(body)
                    val data = root.optJSONArray("data") ?: JSONArray()
                    val models = buildList {
                        for (i in 0 until data.length()) {
                            val obj = data.optJSONObject(i) ?: continue
                            val id = obj.optString("id").trim()
                            if (id.isEmpty()) continue
                            val name = obj.optString("name").ifBlank { id }
                            add(CloudModelInfo(id = id, name = name))
                        }
                    }.sortedBy { it.id.lowercase() }
                    Result.success(models)
                }
            } catch (e: IOException) {
                Result.failure(CloudClientException(CloudError.Network(e.message ?: "Network error")))
            } catch (e: Exception) {
                Result.failure(CloudClientException(CloudError.Parse(e.message ?: "Parse error")))
            }
        }

    /**
     * Streams assistant text deltas. Emits [CloudStreamEvent.Finished] once
     * with any usage object seen on the stream (often on the last chunk).
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<CloudChatMessage>,
    ): Flow<CloudStreamEvent> = flow {
        val payload = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", JSONArray().apply {
                for (m in messages) {
                    put(JSONObject().put("role", m.role).put("content", m.content))
                }
            })
        }
        val request = Request.Builder()
            .url(CloudEndpoints.chatCompletionsUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", appReferer)
            .header("X-Title", appTitle)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val snippet = response.body?.string().orEmpty()
                    throw CloudClientException(CloudSseParser.mapHttpCode(response.code, snippet))
                }
                val source = response.body?.source()
                    ?: throw CloudClientException(CloudError.Parse("Empty response body"))
                val reader = BufferedReader(source.inputStream().reader(Charsets.UTF_8))
                var lastUsage: CloudUsage? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    CloudSseParser.usageFromDataLine(data)?.let { lastUsage = it }
                    when (val delta = CloudSseParser.deltaFromDataLine(data)) {
                        null -> break
                        "" -> Unit
                        else -> emit(CloudStreamEvent.Delta(delta))
                    }
                }
                emit(CloudStreamEvent.Finished(lastUsage))
            }
        } catch (e: CloudClientException) {
            throw e
        } catch (e: IOException) {
            throw CloudClientException(CloudError.Network(e.message ?: "Network error"), e)
        } catch (e: Exception) {
            throw CloudClientException(CloudError.Parse(e.message ?: "Stream error"), e)
        }
    }.flowOn(Dispatchers.IO)

    private fun authedGet(url: String, apiKey: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", appReferer)
            .header("X-Title", appTitle)
            .get()
            .build()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}

class CloudClientException(
    val error: CloudError,
    cause: Throwable? = null,
) : Exception(error.userMessage(), cause)
