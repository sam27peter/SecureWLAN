package com.sam.securewlan.network

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class PairingDetails(
    val host: String,
    val port: Int
)

object PairingQr {

    private const val PREFIX = "SECUREWLAN"
    private const val VERSION = "1"

    fun createBitmap(
        host: String,
        port: Int = TcpServer.PORT,
        size: Int = 640
    ): Bitmap? {
        return try {
            val payload = "$PREFIX|$VERSION|$host|$port"

            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )

            val matrix = QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints
            )

            val pixels = IntArray(size * size)

            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] =
                        if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }

            Bitmap.createBitmap(
                pixels,
                size,
                size,
                Bitmap.Config.ARGB_8888
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parse(payload: String): PairingDetails? {
        val parts = payload.trim().split("|")

        if (parts.size != 4) return null
        if (parts[0] != PREFIX || parts[1] != VERSION) return null

        val host = parts[2]
        val octets = host.split(".")

        // Accept IPv4 addresses only; do not accept arbitrary QR contents.
        if (octets.size != 4) return null

        for (octet in octets) {
            val value = octet.toIntOrNull() ?: return null
            if (value !in 0..255) return null
        }

        val port = parts[3].toIntOrNull() ?: return null

        // This version of SecureWLAN supports the existing TCP port only.
        if (port != TcpServer.PORT) return null

        return PairingDetails(host, port)
    }
}
