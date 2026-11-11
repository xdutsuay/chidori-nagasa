package com.xdutsuay.chidori.computer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build

/**
 * Guest `/etc/resolv.conf` nameservers for COS.
 *
 * Prefers the active Android network's DNS (Wi‑Fi or cellular) so apk/curl inside
 * proot follow the same path as the phone; falls back to [CosConstants.GUEST_NAMESERVERS]
 * when LinkProperties are empty (VPN quirks, offline, older APIs).
 */
object CosGuestDns {

    /**
     * Ordered unique IPv4/IPv6 literal nameservers for [CosProvisioning.resolvConf].
     * Always non-empty (falls back to pinned public resolvers).
     */
    fun nameservers(
        linkDns: List<String>,
        fallback: List<String> = CosConstants.GUEST_NAMESERVERS,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in linkDns) {
            val s = raw.trim()
            if (s.isNotEmpty()) seen.add(s)
        }
        if (seen.isEmpty()) {
            for (f in fallback) {
                val s = f.trim()
                if (s.isNotEmpty()) seen.add(s)
            }
        }
        return seen.toList().ifEmpty { CosConstants.GUEST_NAMESERVERS }
    }

    /** Read DNS from the active network; empty when unavailable. */
    fun readLinkDns(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val props: LinkProperties = cm.getLinkProperties(network) ?: return emptyList()
        props.dnsServers.mapNotNull { it.hostAddress?.trim()?.takeIf { a -> a.isNotEmpty() } }
    }.getOrDefault(emptyList())

    fun nameserversFor(context: Context): List<String> =
        nameservers(readLinkDns(context))

    /** True when [Build.VERSION] can expose LinkProperties DNS (always on our minSdk). */
    fun supportsLinkDns(): Boolean = Build.VERSION.SDK_INT >= 23
}
