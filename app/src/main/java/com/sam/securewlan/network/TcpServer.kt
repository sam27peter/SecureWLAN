package com.sam.securewlan.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

class TcpServer(
    private val receiveDirectory: File,
    private val pairingToken: ByteArray,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object {
        const val PORT = 8988
        private const val MAGIC = 0x53574633 // SWF3
        private const val MAX_FILE_SIZE = 500L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private const val SHA256_LENGTH = 32
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val MAX_FILENAME_LENGTH = 150
        private const val MAX_MIME_LENGTH = 200
        private const val MAX_KEY_BYTES = 512
        private const val GCM_TAG_BYTES = 16
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var running = false

    init { require(pairingToken.size == 32) { "Pairing token must be 32 bytes." } }

    fun start() {
        if (running) return
        running = true
        try {
            executor.execute {
                try {
                    if (!receiveDirectory.exists() && !receiveDirectory.mkdirs()) {
                        throw IOException("Cannot create receive directory.")
                    }
                    ServerSocket(PORT).use { server ->
                        serverSocket = server
                        onStatus("Listening on port $PORT")
                        while (running) {
                            val client = server.accept()
                            activeClient = client
                            try {
                                client.use { socket ->
                                    socket.soTimeout = SOCKET_TIMEOUT_MS
                                    socket.tcpNoDelay = true
                                    onStatus("Secure connection from ${socket.inetAddress.hostAddress}")
                                    handleClient(socket)
                                }
                            } catch (e: Exception) {
                                if (running) onStatus("Transfer failed: ${e.message ?: "Unknown error"}")
                            } finally {
                                activeClient = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running) onStatus("Server error: ${e.message ?: "Unknown error"}")
                } finally {
                    running = false
                    serverSocket = null
                    activeClient = null
                    onStatus("Server stopped")
                }
            }
        } catch (e: Exception) {
            running = false
            onStatus("Unable to start server: ${e.message ?: "Unknown error"}")
        }
    }

    private fun handleClient(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        var partialFile: File? = null
        try {
            if (input.readInt() != MAGIC) throw IOException("Unsupported protocol. Update both devices.")
            val clientKeyLength = input.readInt()
            if (clientKeyLength !in 1..MAX_KEY_BYTES) throw IOException("Invalid sender public key.")
            val clientPublic = ByteArray(clientKeyLength)
            input.readFully(clientPublic)

            val keyPair = SecureTransferCrypto.newKeyPair()
            val serverPublic = keyPair.public.encoded
            val transcript = SecureTransferCrypto.transcript(clientPublic, serverPublic)
            output.writeInt(serverPublic.size)
            output.write(serverPublic)
            output.write(SecureTransferCrypto.hmac(
                pairingToken, "server".toByteArray(Charsets.UTF_8) + transcript
            ))
            output.flush()

            val clientProof = ByteArray(32)
            input.readFully(clientProof)
            val expectedClientProof = SecureTransferCrypto.hmac(
                pairingToken, "client".toByteArray(Charsets.UTF_8) + transcript
            )
            if (!SecureTransferCrypto.constantTimeEquals(clientProof, expectedClientProof)) {
                throw IOException("Sender authentication failed. Scan the current receiver QR.")
            }

            val shared = SecureTransferCrypto.sharedSecret(
                keyPair, SecureTransferCrypto.decodePublicKey(clientPublic)
            )
            val aesKey = SecureTransferCrypto.deriveAesKey(shared, pairingToken, transcript)

            val fileNameRaw = input.readUTF()
            val mimeType = input.readUTF()
            val fileSize = input.readLong()
            if (fileNameRaw.length > MAX_FILENAME_LENGTH) throw IOException("Filename is too long.")
            if (mimeType.isBlank() || mimeType.length > MAX_MIME_LENGTH) throw IOException("Invalid MIME type.")
            if (fileSize <= 0L || fileSize > MAX_FILE_SIZE) throw IOException("Invalid file size. Maximum supported size is 500 MB.")

            val expectedDigest = ByteArray(SHA256_LENGTH)
            input.readFully(expectedDigest)
            val noncePrefix = ByteArray(8)
            input.readFully(noncePrefix)
            val fileName = sanitizeFileName(fileNameRaw)
            onStatus("Receiving encrypted file $fileName (${formatBytes(fileSize)})")

            val finalFile = createUniqueFile(fileName)
            partialFile = File(receiveDirectory, ".${finalFile.name}.part")
            if (partialFile!!.exists() && !partialFile!!.delete()) throw IOException("Cannot clear previous partial file.")

            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var chunkIndex = 0L
            FileOutputStream(partialFile!!).use { destination ->
                while (received < fileSize) {
                    if (!running) throw IOException("Receiver stopped.")
                    val expectedPlainLength = minOf(BUFFER_SIZE.toLong(), fileSize - received).toInt()
                    val encryptedLength = input.readInt()
                    if (encryptedLength != expectedPlainLength + GCM_TAG_BYTES) {
                        throw IOException("Invalid encrypted chunk length.")
                    }
                    val encrypted = ByteArray(encryptedLength)
                    input.readFully(encrypted)
                    val plain = try {
                        SecureTransferCrypto.decryptChunk(aesKey, noncePrefix, chunkIndex, expectedPlainLength, encrypted)
                    } catch (_: AEADBadTagException) {
                        throw IOException("Encrypted chunk authentication failed. File rejected.")
                    }
                    destination.write(plain)
                    digest.update(plain)
                    received += plain.size
                    chunkIndex++
                }
                destination.flush()
                destination.fd.sync()
            }

            if (partialFile!!.length() != fileSize) throw IOException("Received file size verification failed.")
            val actualDigest = digest.digest()
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) throw IOException("SHA-256 verification failed.")
            if (!partialFile!!.renameTo(finalFile)) throw IOException("Could not finalize the verified received file.")
            partialFile = null

            val message = "Received: $fileName\nType: $mimeType\nSize: ${formatBytes(fileSize)}\n" +
                    "AES-256-GCM authenticated\nSHA-256 verified: ${actualDigest.toHex()}\n" +
                    "Saved to: ${finalFile.absolutePath}"
            sendResponse(output, true, message)
            onMessage(message)
            onStatus("Encrypted transfer completed and verified: $fileName")
        } catch (e: Exception) {
            try { partialFile?.let { if (it.exists()) it.delete() } } catch (_: Exception) {}
            try { sendResponse(output, false, e.message ?: "File transfer failed.") } catch (_: Exception) {}
            throw e
        }
    }

    private fun sendResponse(output: DataOutputStream, success: Boolean, message: String) {
        output.writeBoolean(success)
        output.writeUTF(message.take(4000))
        output.flush()
    }

    private fun sanitizeFileName(input: String): String =
        input.replace('\\', '/').substringAfterLast('/').trim()
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(MAX_FILENAME_LENGTH)
            .trim().trim('.').ifBlank { "received_file" }

    private fun createUniqueFile(fileName: String): File {
        if (!receiveDirectory.exists() && !receiveDirectory.mkdirs()) throw IOException("Cannot create receive directory.")
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var candidate = File(receiveDirectory, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(receiveDirectory, "${base}_$index$extension")
            index++
        }
        return candidate
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> String.format("%.2f KB", bytes / 1_000.0)
        bytes < 1_000_000_000L -> String.format("%.2f MB", bytes / 1_000_000.0)
        else -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun stop() {
        running = false
        try { activeClient?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }
}
