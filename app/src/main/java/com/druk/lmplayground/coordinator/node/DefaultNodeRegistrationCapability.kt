package com.druk.lmplayground.coordinator.node

import android.content.Context
import android.util.Log
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.transport.CoordinatorApi
import com.druk.lmplayground.coordinator.transport.NodeRegisterPayload
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Real node-mode registration (protocol §2.5 / NODE_MODE_SPIKE.md).
 * Starts [NodeService] (foreground host for [NodeOpenAiServer]), POSTs companion
 * `/node/register`, heartbeats.
 */
class DefaultNodeRegistrationCapability(
    context: Context,
    private val api: CoordinatorApi,
    private val bridge: () -> NodeInferenceBridge = { NodeInferenceHub.bridge },
    private val lanIpv4: () -> String? = { primaryIpv4() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : NodeRegistrationCapability {

    override val isSupported: Boolean = true

    private val appContext = context.applicationContext
    private val mu = Mutex()
    private var registeredInstance: InstanceId? = null
    private var nodeId: String? = null
    private var heartbeatJob: Job? = null

    override suspend fun registerAsNode(instanceId: InstanceId): Result<Unit> = mu.withLock {
        val model = bridge().currentModel()
            ?: return Result.failure(IllegalStateException("Load an on-device model before offering this phone as a node"))
        val ip = lanIpv4()
            ?: return Result.failure(IllegalStateException("No LAN IPv4 address — check Wi‑Fi"))
        unregisterLocked()
        val port = try {
            NodeService.startAndAwaitPort(appContext)
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val id = "nagasa-${instanceId.value}"
        nodeId = id
        val apiBase = "http://$ip:$port/v1"
        val payload = NodeRegisterPayload(
            nodeId = id,
            displayName = model.displayName,
            apiBase = apiBase,
            models = listOf(model.id),
            contextLength = null,
            approxTokensPerSec = null,
            batteryPct = null,
            charging = null,
            available = true,
        )
        return try {
            api.registerAsNode(instanceId, payload)
            registeredInstance = instanceId
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    val mid = nodeId ?: break
                    val inst = registeredInstance ?: break
                    runCatching {
                        api.nodeHeartbeat(
                            inst,
                            nodeId = mid,
                            load = 0.0,
                            available = bridge().currentModel() != null,
                            models = bridge().currentModel()?.let { listOf(it.id) },
                        )
                    }.onFailure { Log.w(TAG, "heartbeat failed", it) }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            unregisterLocked()
            Result.failure(e)
        }
    }

    override suspend fun unregisterAsNode(instanceId: InstanceId) = mu.withLock {
        if (registeredInstance == instanceId || registeredInstance == null) {
            unregisterLocked()
        }
    }

    private suspend fun unregisterLocked() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val inst = registeredInstance
        val id = nodeId
        if (inst != null && id != null) {
            runCatching { api.unregisterAsNode(inst, id) }
        }
        val shouldStopHost = registeredInstance != null || nodeId != null
        registeredInstance = null
        nodeId = null
        if (shouldStopHost) {
            NodeService.stop(appContext)
        }
    }

    companion object {
        private const val TAG = "NodeRegistration"
        private const val HEARTBEAT_MS = 15_000L

        fun primaryIpv4(): String? {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            return null
        }
    }
}
