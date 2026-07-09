package com.druk.lmplayground.coordinator.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.druk.lmplayground.coordinator.model.DiscoveredInstance
import com.druk.lmplayground.coordinator.model.InstanceId
import com.druk.lmplayground.coordinator.model.ProtocolVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

private const val TAG = "InstanceDiscovery"
private const val SERVICE_TYPE = "_chidori._tcp."

/**
 * Finds `lclreason` desktop instances on the local network. Protocol §2.1:
 * mDNS/NSD is the primary path, manual host:port entry is a required
 * fallback (not optional) because mDNS reliability varies across Android
 * OEM skins.
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
 * Written against WIRE_CONTRACT.md's discovery section (TXT keys:
 * protocol_version, instance_id, pairing_required, display_name) — that
 * draft is not yet reconciled against what `lclreason` actually
 * advertises, so the TXT-record key names here may need to change once
 * that reconciliation happens (protocol §1.3).
 *
 * NOT BUILD-VERIFIED: NSD/mDNS resolution behavior is notoriously
 * inconsistent across Android OEM skins (this is exactly why protocol
 * §2.1 requires the manual host:port fallback, not just this path) — treat
 * this implementation as a solid first draft that needs real-device
 * testing per TEST_PLAN.md §2.2 and §4, not as verified-working code.
 */
class NsdInstanceDiscovery(context: Context) : InstanceDiscovery {

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val discovered = MutableStateFlow<Map<String, DiscoveredInstance>>(emptyMap())
    private var listener: NsdManager.DiscoveryListener? = null

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
                        val attrs = info.attributes
                        fun attr(key: String): String? = attrs[key]?.toString(Charsets.UTF_8)

                        val instanceIdRaw = attr("instance_id") ?: return
                        val protocolVersionRaw = attr("protocol_version") ?: return
                        val pairingRequired = attr("pairing_required")?.toBooleanStrictOrNull() ?: true
                        val displayName = attr("display_name") ?: info.serviceName

                        val instance = DiscoveredInstance(
                            instanceId = InstanceId(instanceIdRaw),
                            displayName = displayName,
                            host = info.host?.hostAddress ?: return,
                            port = info.port,
                            protocolVersion = ProtocolVersion(protocolVersionRaw),
                            pairingRequired = pairingRequired,
                        )
                        discovered.value = discovered.value + (instanceIdRaw to instance)
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
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }
        }
        listener = discoveryListener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    override fun stopDiscovery() {
        listener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
                .onFailure { e -> Log.w(TAG, "stopServiceDiscovery failed", e) }
        }
        listener = null
        discovered.value = emptyMap()
    }
}
