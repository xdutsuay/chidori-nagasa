package com.xdutsuay.chidori.computer.api

import android.content.Context
import com.xdutsuay.chidori.computer.desktop.DesktopBridges
import com.xdutsuay.chidori.tools.CosConfirmGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Process-wide Computer API sessions (USB and Wi-Fi), reading the same desktop as the chat tools. */
object ComputerApis {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var instance: ComputerApiShare? = null
    @Volatile private var wifiInstance: WifiApiShare? = null
    @Volatile private var controlInstance: ComputerControl? = null
    @Volatile private var relayInstance: ApiApprovalRelay? = null

    private fun control(context: Context): ComputerControl =
        controlInstance ?: synchronized(this) {
            controlInstance ?: ToolComputerControl(context.applicationContext).also {
                controlInstance = it
                approvalRelay(context)
            }
        }

    /**
     * Mirrors pending Computer API approvals of [CosConfirmGate.shared] onto the Allow / Deny
     * notification, and denies the pending one once neither the USB nor the Wi-Fi API is shared.
     */
    fun approvalRelay(context: Context): ApiApprovalRelay =
        relayInstance ?: synchronized(this) {
            relayInstance ?: run {
                val app = context.applicationContext
                val gate = CosConfirmGate.shared
                val relay = ApiApprovalRelay(gate::resolve, ApiApprovalNotification.AndroidPoster(app))
                relayInstance = relay
                scope.launch { gate.pending.collect { relay.onPending(it) } }
                scope.launch {
                    combine(share(app).state, wifi(app).state) { usb, wifi ->
                        usb is ApiShareState.Shared || wifi is WifiShareState.Shared
                    }.distinctUntilChanged().filter { shared -> !shared }.collect { relay.sharingStopped() }
                }
                relay
            }
        }

    fun share(context: Context): ComputerApiShare =
        instance ?: synchronized(this) {
            instance ?: ComputerApiShare(
                scope = scope,
                bridge = { DesktopBridges.bridge },
                control = control(context),
            ).also { instance = it }
        }

    fun wifi(context: Context): WifiApiShare =
        wifiInstance ?: synchronized(this) {
            val app = context.applicationContext
            val vault = TlsIdentityVault(app)
            wifiInstance ?: WifiApiShare(
                scope = scope,
                bridge = { DesktopBridges.bridge },
                identity = vault::loadOrCreate,
                wifiAddress = { WifiAddress.current(app) },
                control = control(app),
            ).also { wifiInstance = it }
        }
}
