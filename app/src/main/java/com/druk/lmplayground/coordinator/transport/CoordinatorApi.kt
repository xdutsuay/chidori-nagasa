package com.druk.lmplayground.coordinator.transport

import com.druk.lmplayground.coordinator.model.AgentMode
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ProtocolVersion
import com.druk.lmplayground.coordinator.model.RemoteChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Result of a successful `/pairing/confirm` call — see WIRE_CONTRACT.md. */
data class PairingConfirmation(val authToken: String, val protocolVersion: ProtocolVersion)

/**
 * The actual HTTP(S)/WebSocket client against one paired `lclreason`
 * instance (protocol §2.1, §2.3, §2.4). This is client-mode only — node
 * mode (protocol §2.5) has its own registration surface, see
 * `coordinator.node`.
 *
 * Every call here is a live wire-contract usage: if you're changing a
 * method signature in a way that changes what goes over the network,
 * that's a CHIDORI_PROTOCOL.md §2 change and needs the notify/acknowledge
 * process in §1.3.
 */
interface CoordinatorApi {
    fun observeConnectionState(instanceId: InstanceId): Flow<CoordinatorConnectionState>

    suspend fun negotiateProtocolVersion(instanceId: InstanceId, clientVersion: ProtocolVersion): ProtocolVersion

    suspend fun beginPairing(instanceId: InstanceId, host: String, port: Int): Boolean

    suspend fun confirmPairing(instanceId: InstanceId, code: String): PairingConfirmation?

    suspend fun revokePairing(instanceId: InstanceId)

    suspend fun getStatus(instanceId: InstanceId): CoordinatorStatus

    suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary>

    suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail

    fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage>

    suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String)
}

private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

/**
 * OkHttp-backed implementation (this app already depends on okhttp3 for
 * downloads — no new networking dependency needed for this module) against
 * WIRE_CONTRACT.md's draft REST shape. Uses `org.json` for request/response
 * bodies rather than adding a new serialization dependency, since the
 * payloads here are small and hand-rolled parsing keeps the diff to
 * existing `libs.versions.toml` entries at zero.
 *
 * IMPORTANT: WIRE_CONTRACT.md is an unreconciled draft (see that file's
 * closing section) — every endpoint path and field name below is a
 * proposal, not a confirmed contract with `lclreason`. Do not treat a
 * successful compile of this file as confirmation the endpoints exist;
 * that only happens once `internal/api` on the `lclreason` side is
 * checked against this draft (ROADMAP.md Phase 1) and reconciled into a
 * `CHIDORI_PROTOCOL.md` amendment.
 *
 * Status/run streaming (WS) is left as REST polling for this first draft
 * (`getStatus`/`listRuns` are plain suspend calls, not Flows) — the
 * `/coordinator/status/stream` and `/runs/{id}/stream` WebSocket upgrades
 * in WIRE_CONTRACT.md aren't implemented yet; `observeRemoteChat` is the
 * one path that does use a live OkHttp WebSocket since chat has no
 * meaningful polling fallback.
 */
class OkHttpCoordinatorApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    /** Supplies the bearer token for a paired instance; wired to PairedInstanceStore by the caller. */
    private val authTokenProvider: suspend (InstanceId) -> String? = { null },
    /** Resolves a paired instance's current host:port; wired to PairedInstanceStore by the caller. */
    private val endpointProvider: suspend (InstanceId) -> Pair<String, Int>? = { null },
) : CoordinatorApi {

    private val connectionStates = mutableMapOf<String, MutableStateFlow<CoordinatorConnectionState>>()

    private fun connectionStateFlow(instanceId: InstanceId) =
        connectionStates.getOrPut(instanceId.value) { MutableStateFlow(CoordinatorConnectionState.DISCONNECTED) }

    override fun observeConnectionState(instanceId: InstanceId): Flow<CoordinatorConnectionState> =
        connectionStateFlow(instanceId).asStateFlow()

    private suspend fun baseUrl(instanceId: InstanceId): String? {
        val (host, port) = endpointProvider(instanceId) ?: return null
        return "http://$host:$port"
    }

    private suspend fun authedRequest(instanceId: InstanceId, path: String): Request.Builder? {
        val base = baseUrl(instanceId) ?: return null
        val builder = Request.Builder().url("$base$path")
        authTokenProvider(instanceId)?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    override suspend fun negotiateProtocolVersion(
        instanceId: InstanceId,
        clientVersion: ProtocolVersion,
    ): ProtocolVersion {
        val request = authedRequest(instanceId, "/version")
            ?.header("X-Chidori-Protocol-Version", clientVersion.value)
            ?.build()
            ?: return clientVersion // no endpoint known yet; caller will surface DISCONNECTED separately

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
                    return@use clientVersion
                }
                val recommended = response.body?.string()?.let { JSONObject(it) }
                    ?.let { json -> if (json.has("recommended")) json.getString("recommended") else null }
                if (recommended == null) {
                    connectionStateFlow(instanceId).value = CoordinatorConnectionState.UNSUPPORTED_VERSION
                    clientVersion
                } else {
                    connectionStateFlow(instanceId).value = CoordinatorConnectionState.CONNECTED
                    ProtocolVersion(recommended)
                }
            }
        }.getOrElse {
            // Degrade gracefully per protocol §2.3 — never throw out of this
            // call just because the network hiccupped.
            connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
            clientVersion
        }
    }

    override suspend fun beginPairing(instanceId: InstanceId, host: String, port: Int): Boolean {
        val request = Request.Builder()
            .url("http://$host:$port/pairing/begin")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun confirmPairing(instanceId: InstanceId, code: String): PairingConfirmation? {
        val base = baseUrl(instanceId) ?: return null
        val body = JSONObject().put("code", code).toString()
        val request = Request.Builder()
            .url("$base/pairing/confirm")
            .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                // WIRE_CONTRACT.md's draft doesn't specify the token field name yet
                // beyond "a bearer token issued at pairing confirmation" — using
                // "auth_token" as the working assumption pending reconciliation.
                val token = (if (json.has("auth_token")) json.getString("auth_token") else null)
                    ?: return@use null
                val version = if (json.has("protocol_version")) json.getString("protocol_version") else ProtocolVersion.CURRENT.value
                PairingConfirmation(authToken = token, protocolVersion = ProtocolVersion(version))
            }
        }.getOrNull()
    }

    override suspend fun revokePairing(instanceId: InstanceId) {
        val base = baseUrl(instanceId) ?: return
        val request = Request.Builder()
            .url("$base/pairing/${instanceId.value}")
            .delete()
            .build()
        runCatching { client.newCall(request).execute().close() }
        connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
    }

    override suspend fun getStatus(instanceId: InstanceId): CoordinatorStatus {
        val request = authedRequest(instanceId, "/coordinator/status")?.build()
            ?: return CoordinatorStatus.ERROR
        return runCatching {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                when (json.optString("status")) {
                    "running" -> CoordinatorStatus.RUNNING
                    "error" -> CoordinatorStatus.ERROR
                    else -> CoordinatorStatus.IDLE
                }
            }
        }.getOrDefault(CoordinatorStatus.ERROR)
    }

    override suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary> {
        val request = authedRequest(instanceId, "/runs?limit=50")?.build() ?: return emptyList()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val runsArray = json.optJSONArray("runs") ?: return@use emptyList()
                (0 until runsArray.length()).map { i ->
                    val run = runsArray.getJSONObject(i)
                    run.toAgentRunSummary()
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail {
        val request = authedRequest(instanceId, "/runs/$runId")?.build()
            ?: throw IOException("No known endpoint for $instanceId")
        return client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: throw IOException("Empty run detail response"))
            val summary = json.getJSONObject("summary").toAgentRunSummary()
            val logTailArray = json.optJSONArray("log_tail")
            val logTail = logTailArray?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            AgentRunDetail(
                summary = summary,
                currentStep = if (json.has("current_step")) json.getString("current_step") else null,
                logTail = logTail,
            )
        }
    }

    private fun JSONObject.toAgentRunSummary() = AgentRunSummary(
        runId = getString("run_id"),
        mode = when (optString("mode")) {
            "agent" -> AgentMode.AGENT
            "plan" -> AgentMode.PLAN
            "debug" -> AgentMode.DEBUG
            else -> AgentMode.ASK
        },
        startedAtEpochMillis = optLong("started_at", 0L),
        state = when (optString("state")) {
            "completed" -> AgentRunState.COMPLETED
            "failed" -> AgentRunState.FAILED
            else -> AgentRunState.RUNNING
        },
    )

    override fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage> {
        // WebSocket-backed chat stream per WIRE_CONTRACT.md's `WS /chat/stream`.
        // TODO(coordinator, Phase 3): wire an OkHttp WebSocketListener here once
        // Phase 1's reconciliation confirms the endpoint path/payload shape —
        // implementing against an unconfirmed WS contract risks writing
        // reconnect/backoff logic against a shape that changes under it.
        TODO("Not implemented — see ROADMAP.md Phase 3. Requires the reconciled wire contract from Phase 1 first.")
    }

    override suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String) {
        TODO("Not implemented — see ROADMAP.md Phase 3.")
    }
}

/** Not currently used by [OkHttpCoordinatorApi] but reserved for pairing-code UUID generation on the UI side. */
internal fun newClientNonce(): String = UUID.randomUUID().toString()
