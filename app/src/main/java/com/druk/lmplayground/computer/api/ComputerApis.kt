package com.druk.lmplayground.computer.api

import com.druk.lmplayground.computer.desktop.DesktopBridges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide Computer API session, reading the same desktop as the chat tools. */
object ComputerApis {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val share: ComputerApiShare by lazy { ComputerApiShare(scope, { DesktopBridges.bridge }) }
}
