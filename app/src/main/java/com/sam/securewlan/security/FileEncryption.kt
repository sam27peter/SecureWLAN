package com.sam.securewlan.security

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM primitives for SecureWLAN file-transfer encryption.
 *
 * IMPORTANT:
 * - Sender and receiver must obtain the SAME secret key through a secure
 *   key-agreement/derivation protocol before using this helper.
 * - Never send the AES key in plaintext or put it in the pairing QR code.
 * - A nonce must NEVER be reused with the same key.
 * - GCM ciphertext returned by encrypt() includes the authentication tag.
 *
 * This file provides crypto primitives only. TcpClient and TcpServer must
 * both be updated to exchange/derive a shared key and encrypt/decrypt transfer
 * chunks before the app's file transfer is actually encrypted.
 */
object FileEncryption {

    const val KEY_SIZE_BITS = 256
    const val NONCE_SIZE_BYTES = 12
    const val NONCE_PREFIX_SIZE_BYTES = 8
    const val TAG_SIZE_BITS = 128
    const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    private val secureRandom = SecureRandom()

    /** Generates a fresh AES-256 key. Do not transmit this key directly. */
    @JvmStatic
    fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KEY_ALGORITHM)
        generator.init(KEY_SIZE_BITS, secureRandom)
        return generator.generateKey()
    }

    /** Creates a fresh 96-bit nonce for a single encryption operation. */
    @JvmStatic
    fun generateNonce(): ByteArray =
        ByteArray(NONCE_SIZE_BYTES).also(secureRandom::nextBytes)

    /** Creates a random 64-bit prefix for per-chunk nonces. */
    @JvmStatic
    fun generateNoncePrefix(): ByteArray =
        ByteArray(NONCE_PREFIX_SIZE_BYTES).also(secureRandom::nextBytes)

    /**
     * Creates a 96-bit nonce from an 8-byte random transfer prefix and a
     * 32-bit chunk index. Never reuse an index with the same key and prefix.
     */
    @JvmStatic
    fun nonceForChunk(prefix: ByteArray, chunkIndex: Long): ByteArray {
        require(prefix.size == NONCE_PREFIX_SIZE_BYTES) {
            "Nonce prefix must be exactly $NONCE_PREFIX_SIZE_BYTES bytes."
        }
        require(chunkIndex in 0L..0xFFFF_FFFFL) {
            "Chunk index is outside the supported nonce range."
        }

        return ByteBuffer.allocate(NONCE_SIZE_BYTES)
            .put(prefix)
            .putInt(chunkIndex.toInt())
            .array()
    }

    /**
     * Encrypts one byte array using AES-256-GCM.
     * The returned ciphertext has the 16-byte authentication tag appended.
     * Associated data is authenticated but is not encrypted.
     */
    @JvmStatic
    @JvmOverloads
    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        nonce: ByteArray = generateNonce(),
        associatedData: ByteArray? = null
    ): ByteArray {
        validateKeyAndNonce(key, nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        if (associatedData != null) cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts and authenticates AES-GCM ciphertext.
     * Authentication failure throws a security exception. The caller must
     * reject the transfer/chunk and discard any unverified plaintext.
     */
    @JvmStatic
    @JvmOverloads
    fun decrypt(
        ciphertextAndTag: ByteArray,
        key: SecretKey,
        nonce: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        validateKeyAndNonce(key, nonce)
        require(ciphertextAndTag.size >= TAG_SIZE_BYTES) {
            "Ciphertext is too short to contain a GCM authentication tag."
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        if (associatedData != null) cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertextAndTag)
    }

    private fun validateKeyAndNonce(key: SecretKey, nonce: ByteArray) {
        require(key.algorithm.equals(KEY_ALGORITHM, ignoreCase = true)) {
            "An AES key is required."
        }
        val encodedKey = key.encoded
        require(encodedKey == null || encodedKey.size == KEY_SIZE_BITS / 8) {
            "An AES-256 key (32 bytes) is required."
        }
        require(nonce.size == NONCE_SIZE_BYTES) {
            "AES-GCM nonce must be exactly $NONCE_SIZE_BYTES bytes."
        }
    }
}
