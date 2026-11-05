package com.druk.lmplayground.hybrid

/**
 * CP10 composer router chip: where the hard turn should run.
 * Local = on-device GGUF; Desktop = paired CINDI/node peer; API = cloud OpenAI-compatible.
 */
enum class TurnRoute {
    LOCAL,
    DESKTOP,
    API,
    ;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun fromStorage(raw: String?): TurnRoute = when (raw?.trim()?.lowercase()) {
            "desktop" -> DESKTOP
            "api", "cloud" -> API
            else -> LOCAL
        }
    }
}

/**
 * Resolves the user's chip selection against live pairing / API config.
 * Unpaired Desktop or unconfigured API falls back to [TurnRoute.LOCAL].
 */
object HybridRouter {
    fun resolve(
        selected: TurnRoute,
        desktopPaired: Boolean,
        apiConfigured: Boolean,
    ): TurnRoute = when (selected) {
        TurnRoute.LOCAL -> TurnRoute.LOCAL
        TurnRoute.DESKTOP -> if (desktopPaired) TurnRoute.DESKTOP else TurnRoute.LOCAL
        TurnRoute.API -> if (apiConfigured) TurnRoute.API else TurnRoute.LOCAL
    }
}
