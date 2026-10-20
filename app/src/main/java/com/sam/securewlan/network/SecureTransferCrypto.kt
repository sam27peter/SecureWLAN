package com.sam.securewlan.network

import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH P-256 key agreement authenticated with the short-lived secret in the
 * receiver QR. AES-GCM uses a unique nonce for every chunk in one transfer.
 */
internal object SecureTransferCrypto {
    private const val GCM_TAG_BITS = 128
    private const val NONCE_PREFIX_BYTES = 8
    private const val NONCE_BYTES = 12
    private const val MAX_PUBLIC_KEY_BYTES = 512

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        if (encoded.isEmpty() || encoded.size > MAX_PUBLIC_KEY_BYTES) {
            throw IOException("Invalid key-exchange public key.")
        }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    }

    fun sharedSecret(pair: KeyPair, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair.private)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun deriveAesKey(sharedSecret: ByteArray, token: ByteArray, transcript: ByteArray): ByteArray {
        // HKDF-SHA-256: extract then expand one 32-byte AES-256 key.
        val prk = hmac(token, sharedSecret)
        return hmac(prk, "SecureWLAN AES-256-GCM v2".toByteArray(Charsets.UTF_8) + transcript + byteArrayOf(1))
    }

    fun newNoncePrefix(): ByteArray = ByteArray(NONCE_PREFIX_BYTES).also { SecureRandom().nextBytes(it) }

    fun encryptChunk(key: ByteArray, prefix: ByteArray, index: Long, plain: ByteArray, length: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, index)))
        cipher.updateAAD(aad(index, length))
        return cipher.doFinal(plain, 0, length)
    }

    fun decryptChunk(key: ByteArray, prefix: ByteArray, index: Long, plainLength: Int, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, index)))
        cipher.updateAAD(aad(index, plainLength))
        return cipher.doFinal(encrypted)
    }

    private fun nonce(prefix: ByteArray, index: Long): ByteArray {
        if (prefix.size != NONCE_PREFIX_BYTES || index !in 0..0xFFFFFFFFL) {
            throw IOException("Invalid encrypted chunk nonce.")
        }
        return prefix + byteArrayOf(
            (index ushr 24).toByte(), (index ushr 16).toByte(),
            (index ushr 8).toByte(), index.toByte()
        )
    }

    private fun aad(index: Long, plainLength: Int): ByteArray =
        "SecureWLAN chunk v2".toByteArray(Charsets.UTF_8) +
                byteArrayOf(
                    (index ushr 24).toByte(), (index ushr 16).toByte(),
                    (index ushr 8).toByte(), index.toByte(),
                    (plainLength ushr 24).toByte(), (plainLength ushr 16).toByte(),
                    (plainLength ushr 8).toByte(), plainLength.toByte()
                )

    fun transcript(clientPublic: ByteArray, serverPublic: ByteArray): ByteArray =
        "SecureWLAN ECDH handshake v2".toByteArray(Charsets.UTF_8) +
                intBytes(clientPublic.size) + clientPublic +
                intBytes(serverPublic.size) + serverPublic

    fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte()
    )

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
