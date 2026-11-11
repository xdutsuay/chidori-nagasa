package com.xdutsuay.chidori.coordinator.node

import com.xdutsuay.chidori.coordinator.model.InstanceId

/**
 * CP5 trust helpers for offering this phone as an inference node.
 *
 * - [allowlist]: only paired desktops may receive a register.
 * - [ReplayGuard]: register nonces are single-use on the phone side so a
 *   stuck retry does not mint duplicate offers with the same nonce (desktop
 *   has a matching cache for captured replays).
 */
object NodePeerPolicy {

    fun assertPaired(instanceId: InstanceId, pairedIds: Set<String>) {
        require(instanceId.value in pairedIds) {
            "Refusing node offer: ${instanceId.value} is not a paired desktop"
        }
    }

    /** In-memory single-use register nonces (process lifetime). */
    class ReplayGuard {
        private val seen = LinkedHashSet<String>()
        private val max = 64

        @Synchronized
        fun claim(nonce: String): Boolean {
            val n = nonce.trim()
            if (n.isEmpty() || n in seen) return false
            seen.add(n)
            while (seen.size > max) {
                val first = seen.iterator().next()
                seen.remove(first)
            }
            return true
        }

        @Synchronized
        fun clear() = seen.clear()
    }
}
