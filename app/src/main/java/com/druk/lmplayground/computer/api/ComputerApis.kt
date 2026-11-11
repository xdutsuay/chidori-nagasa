package com.druk.lmplayground.computer.api

import android.content.Context
import com.druk.lmplayground.computer.desktop.DesktopBridges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide Computer API session, reading the same desktop as the chat tools. */
object ComputerApis {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var instance: ComputerApiShare? = null

    fun share(context: Context): ComputerApiShare =
        instance ?: synchronized(this) {
            instance ?: ComputerApiShare(
                scope = scope,
                bridge = { DesktopBridges.bridge },
                control = ToolComputerControl(context.applicationContext),
            ).also { instance = it }
        }
}
