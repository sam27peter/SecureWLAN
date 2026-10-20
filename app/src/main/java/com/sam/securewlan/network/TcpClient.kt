package com.sam.securewlan.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

class TcpClient {
    companion object {
        private const val MAGIC = 0x53574633 // SWF3: authenticated encrypted protocol
        private const val MAX_FILE_SIZE = 500L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private const val SHA256_LENGTH = 32
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val MAX_KEY_BYTES = 512
    }

    private val executor = Executors.newSingleThreadExecutor()

    fun sendFile(
        context: Context,
        uri: Uri,
        host: String,
        token: ByteArray,
        port: Int = TcpServer.PORT,
        onProgress: (Int) -> Unit,
        onResult: (String) -> Unit
    ) {
        executor.execute {
            var temporaryFile: File? = null
            val result = try {
                require(token.size == 32) { "Invalid pairing token. Scan the receiver QR again." }
                val resolver = context.contentResolver
                var fileName = "selected_file"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) fileName = cursor.getString(index) ?: fileName
                    }
                }
                fileName = sanitizeDisplayName(fileName)
                val mimeType = (resolver.getType(uri) ?: "application/octet-stream").take(200)
                temporaryFile = File.createTempFile("securewlan_send_", ".tmp", context.cacheDir)
                resolver.openInputStream(uri)?.use { source ->
                    FileOutputStream(temporaryFile!!).use { destination -> source.copyTo(destination, BUFFER_SIZE) }
                } ?: throw IOException("Unable to open selected file.")

                val staged = temporaryFile!!
                val totalBytes = staged.length()
                if (totalBytes <= 0L) throw IOException("The selected file is empty.")
                if (totalBytes > MAX_FILE_SIZE) throw IOException("Maximum supported file size is 500 MB.")
                val digest = sha256(staged)

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.tcpNoDelay = true
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                    output.writeInt(MAGIC)
                    val keyPair = SecureTransferCrypto.newKeyPair()
                    val clientPublic = keyPair.public.encoded
                    if (clientPublic.size > MAX_KEY_BYTES) throw IOException("Unexpected public-key size.")
                    output.writeInt(clientPublic.size)
                    output.write(clientPublic)
                    output.flush()

                    val serverKeyLength = input.readInt()
                    if (serverKeyLength !in 1..MAX_KEY_BYTES) throw IOException("Invalid receiver public key.")
                    val serverPublic = ByteArray(serverKeyLength)
                    input.readFully(serverPublic)
                    val transcript = SecureTransferCrypto.transcript(clientPublic, serverPublic)
                    val serverProof = ByteArray(32)
                    input.readFully(serverProof)
                    val expectedServerProof = SecureTransferCrypto.hmac(
                        token, "server".toByteArray(Charsets.UTF_8) + transcript
                    )
                    if (!SecureTransferCrypto.constantTimeEquals(serverProof, expectedServerProof)) {
                        throw IOException("Receiver authentication failed. Stop and rescan its QR code.")
                    }
                    output.write(SecureTransferCrypto.hmac(
                        token, "client".toByteArray(Charsets.UTF_8) + transcript
                    ))
                    output.flush()

                    val shared = SecureTransferCrypto.sharedSecret(
                        keyPair, SecureTransferCrypto.decodePublicKey(serverPublic)
                    )
                    val aesKey = SecureTransferCrypto.deriveAesKey(shared, token, transcript)
                    val noncePrefix = SecureTransferCrypto.newNoncePrefix()

                    output.writeUTF(fileName)
                    output.writeUTF(mimeType)
                    output.writeLong(totalBytes)
                    output.write(digest)
                    output.write(noncePrefix)

                    var sent = 0L
                    var chunkIndex = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    FileInputStream(staged).use { source ->
                        while (sent < totalBytes) {
                            val wanted = minOf(buffer.size.toLong(), totalBytes - sent).toInt()
                            var count = source.read(buffer, 0, wanted)
                            while (count == 0) count = source.read(buffer, 0, wanted)
                            if (count < 0) throw IOException("Staged file ended unexpectedly.")
                            val encrypted = SecureTransferCrypto.encryptChunk(aesKey, noncePrefix, chunkIndex, buffer, count)
                            output.writeInt(encrypted.size)
                            output.write(encrypted)
                            sent += count
                            chunkIndex++
                            onProgress(((sent * 100) / totalBytes).toInt())
                        }
                    }
                    output.flush()
                    val success = input.readBoolean()
                    val message = input.readUTF()
                    if (!success) throw IOException(message)
                    "Encrypted transfer successful!\n$message\nSHA-256: ${digest.toHex()}"
                }
            } catch (e: Exception) {
                val message = if (e is AEADBadTagException) "Encrypted data authentication failed." else (e.message ?: "Unknown error")
                "Transfer failed: $message"
            } finally {
                try { temporaryFile?.delete() } catch (_: Exception) {}
            }
            onResult(result)
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun sanitizeDisplayName(input: String): String =
        input.replace('\\', '/').substringAfterLast('/').trim()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(150).trim().trim('.')
            .ifBlank { "selected_file" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun shutdown() { executor.shutdownNow() }
}
