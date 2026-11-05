package com.druk.lmplayground.hybrid

/** Outcome of routing one user turn for hybrid Local / Desktop / API. */
data class HybridTurnPlan(
    val selectedRoute: TurnRoute,
    val effectiveRoute: TurnRoute,
    val desktopMentioned: Boolean,
) {
    companion object {
        fun plan(
            userText: String,
            selected: TurnRoute,
            desktopPaired: Boolean,
            apiConfigured: Boolean,
        ): HybridTurnPlan {
            val mentioned = DesktopPeerMentions.hasDesktopMention(userText)
            val chip = if (mentioned && desktopPaired) TurnRoute.DESKTOP else selected
            val effective = HybridRouter.resolve(chip, desktopPaired, apiConfigured)
            return HybridTurnPlan(
                selectedRoute = selected,
                effectiveRoute = effective,
                desktopMentioned = mentioned,
            )
        }
    }
}
