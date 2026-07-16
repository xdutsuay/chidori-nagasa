package com.druk.lmplayground.coordinator.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ProtocolVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "InstanceDiscovery"
// NsdManager.discoverServices appends ".local." itself — a trailing dot here
// double-suffixes the query and silently finds nothing (protocol §2.1).
private const val SERVICE_TYPE = "_chidori._tcp"

/**
 * Finds `lclreason` desktop instances on the local network. Protocol §2.1:
 * mDNS/NSD is the primary path, manual host:port entry is a required
 * fallback (not optional) because mDNS reliability varies across Android
 * OEM skins.
 *
 * Protocol 1.2.0: companion listens on a dedicated port (default 8027);
 * mDNS SRV must advertise that port. Instance/host names are single-label.
 */
interface InstanceDiscovery {
    /**
     * Emits the current set of discovered instances as they appear/disappear.
     * Implementations must de-duplicate by instanceId and drop an instance
     * from the emitted set once its advertisement stops (see TEST_PLAN.md §2.2).
     */
    fun observeDiscoveredInstances(): Flow<List<DiscoveredInstance>>

    fun startDiscovery()
    fun stopDiscovery()
}

/**
 * NSD-backed implementation targeting the `_chidori._tcp.local.` service
 * type (protocol §2.1), using `android.net.nsd.NsdManager`.
 *
 * TXT keys (protocol 1.2.0): protocol_version, instance_id, pairing_required,
 * display_name. When TXT is incomplete but host/port resolve, we probe
 * `GET /version` instead of silently dropping the service (OEM NSD often
 * returns empty attributes).
 */
class NsdInstanceDiscovery(context: Context) : InstanceDiscovery {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val discovered = MutableStateFlow<Map<String, DiscoveredInstance>>(emptyMap())
    private var listener: NsdManager.DiscoveryListener? = null

    // Several OEM WiFi stacks silently drop multicast/mDNS packets without
    // this held for the duration of discovery — no error is surfaced when
    // that happens, discovery just finds nothing (protocol §2.1).
    private var multicastLock: WifiManager.MulticastLock? = null

    private val probeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chidori-mdns-probe").apply { isDaemon = true }
    }

    override fun observeDiscoveredInstances(): Flow<List<DiscoveredInstance>> =
        discovered.map { it.values.toList() }

    override fun startDiscovery() {
        if (listener != null) return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        publishResolved(info)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Service name isn't necessarily the instance_id, so we can't
                // key removal off it directly without re-resolving. Simple
                // first-draft approach: drop anything whose serviceName
                // matches a cached entry's display name. Revisit once this
                // has been exercised against a real lclreason instance —
                // NsdServiceInfo's identity semantics across onServiceLost
                // vs onServiceFound are one of the OEM-inconsistent areas
                // TEST_PLAN.md §2.2 calls out.
                discovered.value = discovered.value.filterValues {
                    it.displayName != serviceInfo.serviceName
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                listener = null
                multicastLock?.let { lock -> runCatching { lock.release() } }
                multicastLock = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }
        }
        listener = discoveryListener
        multicastLock = wifiManager.createMulticastLock("chidori-nsd-discovery").apply {
            setReferenceCounted(true)
            acquire()
        }
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun stopDiscovery() {
        listener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
                .onFailure { e -> Log.w(TAG, "stopServiceDiscovery failed", e) }
        }
        listener = null
        multicastLock?.let { lock -> runCatching { lock.release() } }
        multicastLock = null
        discovered.value = emptyMap()
    }

    private fun publishResolved(info: NsdServiceInfo) {
        val host = preferredHostAddress(info) ?: run {
            Log.w(TAG, "Resolved ${info.serviceName} but host address was null")
            return
        }
        val port = info.port
        if (port <= 0) return

        val attrs = info.attributes
        fun attr(key: String): String? = attrs?.get(key)?.toString(Charsets.UTF_8)

        val instanceIdRaw = attr("instance_id")
        val protocolVersionRaw = attr("protocol_version")
        val pairingRequired = attr("pairing_required")?.toBooleanStrictOrNull() ?: true
        val displayName = attr("display_name") ?: info.serviceName

        if (instanceIdRaw != null && protocolVersionRaw != null) {
            publishInstance(
                DiscoveredInstance(
                    instanceId = InstanceId(instanceIdRaw),
                    displayName = displayName,
                    host = host,
                    port = port,
                    protocolVersion = ProtocolVersion(protocolVersionRaw),
                    pairingRequired = pairingRequired,
                ),
            )
            return
        }

        // TXT incomplete (common on some OEM NSD stacks) — probe GET /version
        // so we still surface the desktop instead of silently dropping it.
        Log.i(TAG, "TXT incomplete for ${info.serviceName}; probing http://$host:$port/version")
        probeExecutor.execute {
            val recommended = probeRecommendedVersion(host, port) ?: return@execute
            val id = instanceIdRaw ?: "discovered:$host:$port"
            publishInstance(
                DiscoveredInstance(
                    instanceId = InstanceId(id),
                    displayName = displayName,
                    host = host,
                    port = port,
                    protocolVersion = ProtocolVersion(protocolVersionRaw ?: recommended),
                    pairingRequired = pairingRequired,
                ),
            )
        }
    }

    private fun publishInstance(instance: DiscoveredInstance) {
        discovered.value = discovered.value + (instance.instanceId.value to instance)
    }

    companion object {
        /**
         * Prefer IPv4 for cleartext LAN HTTP — IPv6 literals need brackets in
         * URLs and many OEM stacks hand back link-local IPv6 first.
         */
        fun preferredHostAddress(info: NsdServiceInfo): String? {
            val host = info.host ?: return null
            when (host) {
                is Inet4Address -> return host.hostAddress
                is Inet6Address -> {
                    // Fall back to IPv6 without zone id if that's all we have.
                    return host.hostAddress?.substringBefore('%')
                }
                else -> {
                    val raw = host.hostAddress?.substringBefore('%') ?: return null
                    return raw
                }
            }
        }

        fun probeRecommendedVersion(host: String, port: Int): String? {
            return try {
                val url = URL("http://$host:$port/version")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    requestMethod = "GET"
                    setRequestProperty("X-Chidori-Protocol-Version", ProtocolVersion.CURRENT.value)
                }
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "version probe $host:$port HTTP ${conn.responseCode}")
                        return null
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    json.optString("recommended").ifBlank {
                        ProtocolVersion.CURRENT.value
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "version probe $host:$port failed: ${e.message}")
                null
            }
        }
    }
}
