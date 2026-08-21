package com.druk.lmplayground.coordinator.transport

import android.util.Log
import com.druk.lmplayground.coordinator.model.AgentMode
import com.druk.lmplayground.coordinator.model.AgentRunDetail
import com.druk.lmplayground.coordinator.model.AgentRunState
import com.druk.lmplayground.coordinator.model.AgentRunSummary
import com.druk.lmplayground.coordinator.model.CoordinatorConnectionState
import com.druk.lmplayground.coordinator.model.CoordinatorStatus
import com.druk.lmplayground.coordinator.model.CoordinatorStatusInfo
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ProtocolVersion
import com.druk.lmplayground.coordinator.model.RemoteChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "CoordinatorApi"

/**
 * Surfaces *why* a pairing attempt failed (protocol §2.2's handshake is the
 * one place a silent failure is actively harmful to debug — see
 * CHIDORI_PROTOCOL.md's discussion of the mDNS/cleartext outage this was
 * added for). [Failure.reason] is shown directly in the pairing-failed
 * dialog (ChidoriScreen.kt), so keep it short and free of anything
 * sensitive (no auth tokens, no full request bodies).
 */
sealed interface PairingOutcome {
    data object Success : PairingOutcome
    data class Failure(val reason: String) : PairingOutcome
}

sealed interface PairingConfirmResult {
    data class Success(val confirmation: PairingConfirmation) : PairingConfirmResult
    data class Failure(val reason: String) : PairingConfirmResult
}

/** Short, UI-safe description of a caught exception — e.g. the platform's
 * `CLEARTEXT_NOT_PERMITTED` IOException, whose message alone identifies the
 * root cause. */
private fun Throwable.describeForUi(): String {
    val name = javaClass.simpleName
    return if (message != null) "$name: $message" else name
}

/**
 * Result of a successful `/pairing/confirm` call — see WIRE_CONTRACT.md.
 * [instanceId] is the server-asserted identity: for manual host:port pairing
 * the client only knows a placeholder id until this response, so callers
 * must re-key their pairing record to this value when it differs.
 */
data class PairingConfirmation(
    val authToken: String,
    val protocolVersion: ProtocolVersion,
    val instanceId: InstanceId?,
)

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

    suspend fun beginPairing(instanceId: InstanceId, host: String, port: Int): PairingOutcome

    suspend fun confirmPairing(instanceId: InstanceId, code: String): PairingConfirmResult

    suspend fun revokePairing(instanceId: InstanceId)

    suspend fun getStatus(instanceId: InstanceId): CoordinatorStatusInfo

    suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary>

    suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail

    fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage>

    suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String)

    /** Node mode (NODE_MODE_SPIKE.md): register this phone as an inference worker. */
    suspend fun registerAsNode(instanceId: InstanceId, payload: NodeRegisterPayload)

    suspend fun nodeHeartbeat(
        instanceId: InstanceId,
        nodeId: String,
        load: Double,
        available: Boolean,
        models: List<String>?,
    )

    suspend fun unregisterAsNode(instanceId: InstanceId, nodeId: String)
}

/** Companion POST /node/register body. */
data class NodeRegisterPayload(
    val nodeId: String,
    val displayName: String,
    val apiBase: String,
    val models: List<String>,
    val contextLength: Int?,
    val approxTokensPerSec: Double?,
    val batteryPct: Int?,
    val charging: Boolean?,
    val available: Boolean,
)

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

    // Every blocking OkHttp execute() must run here, never on the caller's
    // context: ChidoriViewModel invokes these from viewModelScope (the main
    // thread), and Android's default thread policy kills network-on-main
    // with NetworkOnMainThreadException — which runCatching below then
    // reported as a generic "couldn't reach the desktop" failure, making
    // pairing fail on real devices even on a healthy LAN.
    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override suspend fun negotiateProtocolVersion(
        instanceId: InstanceId,
        clientVersion: ProtocolVersion,
    ): ProtocolVersion {
        val request = authedRequest(instanceId, "/version")
            ?.header("X-Chidori-Protocol-Version", clientVersion.value)
            ?.build()
            ?: return clientVersion // no endpoint known yet; caller will surface DISCONNECTED separately

        return runCatching {
            onIo {
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
            }
        }.getOrElse {
            // Degrade gracefully per protocol §2.3 — never throw out of this
            // call just because the network hiccupped.
            connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
            clientVersion
        }
    }

    override suspend fun beginPairing(instanceId: InstanceId, host: String, port: Int): PairingOutcome {
        val request = Request.Builder()
            .url("http://$host:$port/pairing/begin")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        return runCatching {
            onIo {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        PairingOutcome.Success
                    } else {
                        PairingOutcome.Failure("Desktop returned HTTP ${response.code} for /pairing/begin")
                    }
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "beginPairing to $host:$port failed", e)
            PairingOutcome.Failure(e.describeForUi())
        }
    }

    override suspend fun confirmPairing(instanceId: InstanceId, code: String): PairingConfirmResult {
        val base = baseUrl(instanceId)
            ?: return PairingConfirmResult.Failure("No known address for this instance")
        val body = JSONObject().put("code", code).toString()
        val request = Request.Builder()
            .url("$base/pairing/confirm")
            .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        return runCatching {
            onIo {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use PairingConfirmResult.Failure(
                            "Desktop returned HTTP ${response.code} for /pairing/confirm"
                        )
                    }
                    val bodyString = response.body?.string()
                        ?: return@use PairingConfirmResult.Failure("Empty response body from /pairing/confirm")
                    val json = JSONObject(bodyString)
                    // WIRE_CONTRACT.md: the bearer token arrives as "auth_token"
                    // in this response; the mobile client treats a confirm
                    // reply without one as a failed pairing.
                    val token = (if (json.has("auth_token")) json.getString("auth_token") else null)
                        ?: return@use PairingConfirmResult.Failure("Response missing auth_token field")
                    val version = if (json.has("protocol_version")) json.getString("protocol_version") else ProtocolVersion.CURRENT.value
                    val serverInstanceId = if (json.has("instance_id")) InstanceId(json.getString("instance_id")) else null
                    PairingConfirmResult.Success(
                        PairingConfirmation(
                            authToken = token,
                            protocolVersion = ProtocolVersion(version),
                            instanceId = serverInstanceId,
                        )
                    )
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "confirmPairing for ${instanceId.value} failed", e)
            PairingConfirmResult.Failure(e.describeForUi())
        }
    }

    override suspend fun revokePairing(instanceId: InstanceId) {
        // DELETE /pairing/{id} is bearer-authenticated like every other
        // post-pairing call (WIRE_CONTRACT.md) — the desktop 401s an
        // unauthenticated revoke, which used to leave its copy of the token
        // valid after the phone thought it had unpaired.
        val request = authedRequest(instanceId, "/pairing/${instanceId.value}")
            ?.delete()
            ?.build()
            ?: return
        runCatching { onIo { client.newCall(request).execute().close() } }
        connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
    }

    override suspend fun getStatus(instanceId: InstanceId): CoordinatorStatusInfo {
        val request = authedRequest(instanceId, "/coordinator/status")?.build()
            ?: return CoordinatorStatusInfo(CoordinatorStatus.ERROR, "No known endpoint")
        return runCatching {
            onIo {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use CoordinatorStatusInfo(
                            CoordinatorStatus.ERROR,
                            "HTTP ${response.code}",
                        )
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val status = when (json.optString("status")) {
                        "running" -> CoordinatorStatus.RUNNING
                        "error" -> CoordinatorStatus.ERROR
                        else -> CoordinatorStatus.IDLE
                    }
                    val err = json.optString("error_message").takeIf { it.isNotBlank() }
                    CoordinatorStatusInfo(status, err)
                }
            }
        }.getOrDefault(CoordinatorStatusInfo(CoordinatorStatus.ERROR, "Status request failed"))
    }

    override suspend fun listRuns(instanceId: InstanceId): List<AgentRunSummary> {
        val request = authedRequest(instanceId, "/runs?limit=50")?.build() ?: return emptyList()
        return runCatching {
            onIo {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val runsArray = json.optJSONArray("runs") ?: return@use emptyList()
                    (0 until runsArray.length()).map { i ->
                        val run = runsArray.getJSONObject(i)
                        run.toAgentRunSummary()
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getRunDetail(instanceId: InstanceId, runId: String): AgentRunDetail {
        val request = authedRequest(instanceId, "/runs/$runId")?.build()
            ?: throw IOException("No known endpoint for $instanceId")
        return onIo {
            client.newCall(request).execute().use { response ->
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
        currentStep = optString("current_step").takeIf { it.isNotBlank() },
    )

    // One live chat socket per instance ("one logical stream per paired
    // instance" — WIRE_CONTRACT.md). Owned by observeRemoteChat's collector:
    // opened on collect, closed on cancel; sendRemoteChatMessage rides
    // whatever socket is currently open for that instance.
    private val chatSockets = mutableMapOf<String, WebSocket>()

    override fun observeRemoteChat(instanceId: InstanceId): Flow<RemoteChatMessage> = callbackFlow {
        val request = authedRequest(instanceId, "/chat/stream")?.build()
        if (request == null) {
            // No known endpoint (unpaired / no stored host) — surface as
            // DISCONNECTED and end the stream rather than throwing.
            connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
            close()
            return@callbackFlow
        }
        connectionStateFlow(instanceId).value = CoordinatorConnectionState.CONNECTING

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectionStateFlow(instanceId).value = CoordinatorConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching {
                    val json = JSONObject(text)
                    RemoteChatMessage(
                        id = json.getString("id"),
                        fromUser = json.optBoolean("from_user", false),
                        text = json.getString("text"),
                        sentAtEpochMillis = json.optLong("sent_at", System.currentTimeMillis()),
                    )
                }.getOrNull() ?: return // tolerate unknown frames per protocol §2.3
                trySend(message)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionStateFlow(instanceId).value = CoordinatorConnectionState.DISCONNECTED
                close()
            }
        })
        synchronized(chatSockets) { chatSockets[instanceId.value] = socket }

        awaitClose {
            synchronized(chatSockets) {
                if (chatSockets[instanceId.value] === socket) chatSockets.remove(instanceId.value)
            }
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    override suspend fun sendRemoteChatMessage(instanceId: InstanceId, text: String) {
        val socket = synchronized(chatSockets) { chatSockets[instanceId.value] }
            ?: throw IOException("No open chat stream for ${instanceId.value}")
        val payload = JSONObject().put("text", text).toString()
        if (!socket.send(payload)) {
            // send() returns false when the socket is closing/closed or its
            // outbound buffer is unavailable — surface it so the caller can
            // keep the draft and show the disconnected state (PRD §6.4:
            // never silently drop a message).
            throw IOException("Chat stream to ${instanceId.value} is not writable")
        }
    }

    override suspend fun registerAsNode(instanceId: InstanceId, payload: NodeRegisterPayload) {
        val body = JSONObject()
            .put("node_id", payload.nodeId)
            .put("display_name", payload.displayName)
            .put("api_base", payload.apiBase)
            .put("models", org.json.JSONArray(payload.models))
            .put("available", payload.available)
        payload.contextLength?.let { body.put("context_length", it) }
        payload.approxTokensPerSec?.let { body.put("approx_tokens_per_sec", it) }
        payload.batteryPct?.let { body.put("battery_pct", it) }
        payload.charging?.let { body.put("charging", it) }
        val request = authedRequest(instanceId, "/node/register")
            ?.post(body.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            ?.build()
            ?: throw IOException("No endpoint for ${instanceId.value}")
        onIo {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("POST /node/register → HTTP ${response.code}")
                }
            }
        }
    }

    override suspend fun nodeHeartbeat(
        instanceId: InstanceId,
        nodeId: String,
        load: Double,
        available: Boolean,
        models: List<String>?,
    ) {
        val body = JSONObject()
            .put("node_id", nodeId)
            .put("load", load)
            .put("available", available)
        if (models != null) body.put("models", org.json.JSONArray(models))
        val request = authedRequest(instanceId, "/node/heartbeat")
            ?.post(body.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            ?.build()
            ?: throw IOException("No endpoint for ${instanceId.value}")
        onIo {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("POST /node/heartbeat → HTTP ${response.code}")
                }
            }
        }
    }

    override suspend fun unregisterAsNode(instanceId: InstanceId, nodeId: String) {
        val body = JSONObject().put("node_id", nodeId).toString()
        val request = authedRequest(instanceId, "/node/register")
            ?.delete(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            ?.build()
            ?: return
        onIo {
            client.newCall(request).execute().use { /* best-effort */ }
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

/** Not currently used by [OkHttpCoordinatorApi] but reserved for pairing-code UUID generation on the UI side. */
internal fun newClientNonce(): String = UUID.randomUUID().toString()
