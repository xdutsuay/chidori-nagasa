package com.druk.lmplayground.computer.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.net.Inet4Address

/** The Wi-Fi Computer API's TLS identity, kept in app storage encrypted with a Keystore master key. */
class TlsIdentityVault(context: Context) {
    private val app = context.applicationContext
    private val file = File(File(app.filesDir, DIR).apply { mkdirs() }, FILE)
    private val masterKey by lazy {
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    @Synchronized
    fun loadOrCreate(): TlsIdentity {
        if (file.exists()) {
            runCatching {
                encrypted().openFileInput().use { TlsIdentity.fromPem(it.readBytes().toString(Charsets.UTF_8)) }
            }.getOrNull()?.let { return it }
            file.delete()
        }
        val identity = TlsIdentity.generate()
        encrypted().openFileOutput().use { it.write(identity.toPem().toByteArray(Charsets.UTF_8)) }
        return identity
    }

    private fun encrypted() = EncryptedFile.Builder(
        app,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private companion object {
        const val DIR = "computer_api"
        const val FILE = "tls_identity.pem"
    }
}

object WifiAddress {
    /** The private IPv4 address of the active Wi-Fi network, or null when not on Wi-Fi. */
    fun current(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val link = cm.getLinkProperties(network) ?: continue
            link.linkAddresses.map { it.address }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.let { return it.hostAddress }
        }
        return null
    }
}

object QrBitmap {
    fun encode(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
