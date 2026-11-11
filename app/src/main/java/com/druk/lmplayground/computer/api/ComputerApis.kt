package com.druk.lmplayground.computer.api

import android.content.Context
import com.druk.lmplayground.computer.desktop.DesktopBridges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide Computer API sessions (USB and Wi-Fi), reading the same desktop as the chat tools. */
object ComputerApis {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var instance: ComputerApiShare? = null
    @Volatile private var wifiInstance: WifiApiShare? = null
    @Volatile private var controlInstance: ComputerControl? = null

    private fun control(context: Context): ComputerControl =
        controlInstance ?: synchronized(this) {
            controlInstance ?: ToolComputerControl(context.applicationContext).also { controlInstance = it }
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
