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

class TcpClient {

    companion object {
        // SWF2: protocol version 2, including SHA-256 integrity verification.
        private const val MAGIC = 0x53574632
        private const val MAX_FILE_SIZE = 500L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
        private const val SHA256_LENGTH = 32
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_TIMEOUT_MS = 120_000
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** Retained for the existing connection-test button. */
    fun testConnection(
        host: String,
        port: Int = TcpServer.PORT,
        onResult: (String) -> Unit
    ) {
        executor.execute {
            val result = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.trim(), port), 5_000)
                    socket.soTimeout = 5_000

                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()

                    output.write("HELLO_SECUREWLAN\n".toByteArray(Charsets.UTF_8))
                    output.flush()

                    val response = StringBuilder()
                    while (true) {
                        val next = input.read()
                        if (next == -1 || next == '\n'.code) break
                        response.append(next.toChar())
                        if (response.length > 4096) {
                            throw IOException("Response too long.")
                        }
                    }
                    response.toString().ifBlank {
                        "Connection closed without a reply."
                    }
                }
            } catch (e: Exception) {
                "Connection failed: ${e.message ?: "Unknown error"}"
            }
            onResult(result)
        }
    }

    /**
     * Stages the selected document to a temporary file so its exact byte count
     * and SHA-256 digest are known before the protocol header is sent.
     * Progress is reported as 0..100.
     */
    fun sendFile(
        context: Context,
        uri: Uri,
        host: String,
        port: Int = TcpServer.PORT,
        onProgress: (Int) -> Unit,
        onResult: (String) -> Unit
    ) {
        executor.execute {
            var temporaryFile: File? = null
            val result = try {
                val resolver = context.contentResolver
                var fileName = "selected_file"

                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex =
                            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }
                }

                fileName = sanitizeDisplayName(fileName)
                val mimeType = (resolver.getType(uri) ?: "application/octet-stream")
                    .take(200)

                temporaryFile = File.createTempFile(
                    "securewlan_send_",
                    ".tmp",
                    context.cacheDir
                )

                resolver.openInputStream(uri)?.use { source ->
                    FileOutputStream(temporaryFile!!).use { destination ->
                        source.copyTo(destination, BUFFER_SIZE)
                    }
                } ?: throw IOException("Unable to open selected file.")

                val stagedFile = temporaryFile!!
                val totalBytes = stagedFile.length()

                if (totalBytes <= 0L) {
                    throw IOException("The selected file is empty.")
                }
                if (totalBytes > MAX_FILE_SIZE) {
                    throw IOException("Maximum supported file size is 500 MB.")
                }

                val digest = sha256(stagedFile)

                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host.trim(), port),
                        CONNECT_TIMEOUT_MS
                    )
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.tcpNoDelay = true

                    val output = DataOutputStream(
                        BufferedOutputStream(socket.getOutputStream())
                    )
                    val input = DataInputStream(
                        BufferedInputStream(socket.getInputStream())
                    )

                    // Protocol v2 header: magic, UTF filename, UTF MIME type,
                    // byte count, 32-byte SHA-256 digest, then exactly fileSize bytes.
                    output.writeInt(MAGIC)
                    output.writeUTF(fileName)
                    output.writeUTF(mimeType)
                    output.writeLong(totalBytes)
                    output.write(digest)

                    var sentBytes = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    FileInputStream(stagedFile).use { source ->
                        while (sentBytes < totalBytes) {
                            val wanted = minOf(
                                buffer.size.toLong(),
                                totalBytes - sentBytes
                            ).toInt()
                            val count = source.read(buffer, 0, wanted)
                            if (count < 0) {
                                throw IOException("Staged file ended unexpectedly.")
                            }
                            if (count == 0) continue

                            output.write(buffer, 0, count)
                            sentBytes += count
                            onProgress(((sentBytes * 100) / totalBytes).toInt())
                        }
                    }
                    output.flush()

                    val success = input.readBoolean()
                    val message = input.readUTF()
                    if (!success) throw IOException(message)

                    "Transfer successful!\n$message\nSHA-256: ${digest.toHex()}"
                }
            } catch (e: Exception) {
                "Transfer failed: ${e.message ?: "Unknown error"}"
            } finally {
                try {
                    temporaryFile?.delete()
                } catch (_: Exception) {
                    // Best-effort cleanup of the staged source copy.
                }
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

    private fun sanitizeDisplayName(input: String): String {
        val leaf = input.replace('\\', '/').substringAfterLast('/').trim()
        return leaf
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(150)
            .trim()
            .trim('.')
            .ifBlank { "selected_file" }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    fun shutdown() {
        executor.shutdownNow()
    }
}
