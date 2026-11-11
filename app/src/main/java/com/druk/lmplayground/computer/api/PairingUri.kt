package com.druk.lmplayground.computer.api

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The link shown as a QR code for Wi-Fi pairing (slice 3):
 * `chidori-computer://pair?host=<host>&port=<port>&pin=<pin>&code=<code>` with every value
 * URL-encoded (UTF-8). [parse] reverses [build]; it returns null for another scheme or host,
 * a missing or blank field, or a port outside 1..65535.
 */
object PairingUri {
    const val SCHEME = "chidori-computer"
    const val HOST = "pair"

    data class Fields(val host: String, val port: Int, val pin: String, val code: String)

    fun build(fields: Fields): String =
        "$SCHEME://$HOST?host=${enc(fields.host)}&port=${fields.port}&pin=${enc(fields.pin)}&code=${enc(fields.code)}"

    fun parse(uri: String): Fields? {
        val prefix = "$SCHEME://$HOST?"
        if (!uri.startsWith(prefix)) return null
        val params = uri.removePrefix(prefix).split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to (runCatching { dec(pair.substring(i + 1)) }.getOrNull() ?: return null)
        }.toMap()
        val host = params["host"]?.takeIf { it.isNotBlank() } ?: return null
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val pin = params["pin"]?.takeIf { it.isNotBlank() } ?: return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        return Fields(host, port, pin, code)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
