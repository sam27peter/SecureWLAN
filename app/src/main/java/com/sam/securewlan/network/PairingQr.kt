package com.sam.securewlan.network

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom
import android.util.Base64

data class PairingDetails(
    val host: String,
    val port: Int,
    val token: ByteArray
)

object PairingQr {
    private const val PREFIX = "SECUREWLAN"
    private const val VERSION = "2"

    fun newToken(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun createBitmap(host: String, port: Int, token: ByteArray): Bitmap {
        require(token.size == 32) { "Pairing token must be 32 bytes." }
        val payload = "$PREFIX|$VERSION|$host|$port|${Base64.encodeToString(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 640)
        return Bitmap.createBitmap(640, 640, Bitmap.Config.RGB_565).also { bitmap ->
            for (x in 0 until 640) {
                for (y in 0 until 640) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }

    fun parse(contents: String): PairingDetails? {
        return try {
            val parts = contents.trim().split('|')
            if (parts.size != 5 || parts[0] != PREFIX || parts[1] != VERSION) return null
            val host = parts[2]
            val port = parts[3].toInt()
            val token = Base64.decode(parts[4], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            if (host.isBlank() || port != TcpServer.PORT || token.size != 32) return null
            PairingDetails(host, port, token)
        } catch (_: Exception) {
            null
        }
    }
}
