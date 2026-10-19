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
import java.util.concurrent.Executors

class TcpServer(
    private val receiveDirectory: File,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object {
        const val PORT = 8988
        private const val MAGIC = 0x53574631 // "SWF1"
        private const val MAX_FILE_SIZE = 500L * 1024L * 1024L // 500 MB
        private const val BUFFER_SIZE = 64 * 1024
    }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return

        running = true

        try {
            executor.execute {
                try {
                    receiveDirectory.mkdirs()

                    ServerSocket(PORT).use { server ->
                        serverSocket = server
                        onStatus("Listening on port $PORT")

                        while (running) {
                            val client = server.accept()
                            activeClient = client

                            try {
                                client.use { socket ->
                                    socket.soTimeout = 30_000
                                    socket.tcpNoDelay = true

                                    onStatus(
                                        "Connected to " +
                                                socket.inetAddress.hostAddress
                                    )

                                    handleClient(socket)
                                }
                            } catch (e: Exception) {
                                if (running) {
                                    onStatus(
                                        "Transfer failed: " +
                                                (e.message ?: "Unknown error")
                                    )
                                }
                            } finally {
                                activeClient = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        onStatus(
                            "Server error: " +
                                    (e.message ?: "Unknown error")
                        )
                    }
                } finally {
                    running = false
                    serverSocket = null
                    activeClient = null
                    onStatus("Server stopped")
                }
            }
        } catch (e: Exception) {
            running = false
            onStatus("Unable to start server: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        val input = DataInputStream(
            BufferedInputStream(socket.getInputStream())
        )
        val output = DataOutputStream(
            BufferedOutputStream(socket.getOutputStream())
        )

        var destination: File? = null

        try {
            val magic = input.readInt()

            if (magic != MAGIC) {
                val received = StringBuilder()

                // The first four bytes were already read by readInt().
                received.append(
                    byteArrayOf(
                        (magic shr 24).toByte(),
                        (magic shr 16).toByte(),
                        (magic shr 8).toByte(),
                        magic.toByte()
                    ).toString(Charsets.UTF_8)
                )

                while (!received.endsWith("\n") && received.length < 4096) {
                    val next = input.read()
                    if (next == -1) break
                    received.append(next.toChar())
                }

                if (received.toString().trim() == "HELLO_SECUREWLAN") {
                    socket.getOutputStream().write(
                        "ACK: SecureWLAN received message\n".toByteArray(Charsets.UTF_8)
                    )
                    socket.getOutputStream().flush()
                    onMessage("HELLO_SECUREWLAN")
                    onStatus("Test message received successfully")
                    return
                }

                sendResponse(output, false, "Invalid SecureWLAN protocol.")
                return
            }

            val fileName = sanitizeFileName(input.readUTF())
            val mimeType = input.readUTF()
            val fileSize = input.readLong()

            if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
                sendResponse(
                    output,
                    false,
                    "Invalid file size. Maximum supported size is 500 MB."
                )
                return
            }

            if (fileName.isBlank()) {
                sendResponse(output, false, "Invalid filename.")
                return
            }

            // The MIME type is metadata only; do not trust it as proof
            // that the incoming content is really that file type.
            onStatus(
                "Receiving $fileName " +
                        "(${formatBytes(fileSize)})"
            )

            destination = createUniqueFile(fileName)

            var received = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            FileOutputStream(destination).use { fileOutput ->
                while (received < fileSize) {
                    if (!running) {
                        throw IOException("Receiver stopped.")
                    }

                    val bytesToRead = minOf(
                        buffer.size.toLong(),
                        fileSize - received
                    ).toInt()

                    val count = input.read(buffer, 0, bytesToRead)

                    if (count == -1) {
                        throw IOException(
                            "Connection ended before the full file arrived."
                        )
                    }

                    fileOutput.write(buffer, 0, count)
                    received += count
                }

                fileOutput.flush()
            }

            if (destination.length() != fileSize) {
                throw IOException("Received file size verification failed.")
            }

            val message =
                "Received: $fileName\n" +
                        "Type: $mimeType\n" +
                        "Size: ${formatBytes(fileSize)}\n" +
                        "Saved to: ${destination.absolutePath}"

            sendResponse(output, true, message)
            onMessage(message)
            onStatus("Transfer completed: $fileName")
        } catch (e: Exception) {
            destination?.let {
                try {
                    if (it.exists()) it.delete()
                } catch (_: Exception) {
                    // Best-effort cleanup of an incomplete file.
                }
            }

            try {
                sendResponse(
                    output,
                    false,
                    e.message ?: "File transfer failed."
                )
            } catch (_: Exception) {
                // The client may already have disconnected.
            }

            throw e
        }
    }

    private fun sendResponse(
        output: DataOutputStream,
        success: Boolean,
        message: String
    ) {
        output.writeBoolean(success)
        output.writeUTF(message.take(4000))
        output.flush()
    }

    private fun sanitizeFileName(input: String): String {
        val leafName = input
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()

        val safeName = leafName
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .take(150)
            .trim()
            .trim('.')

        return safeName.ifBlank { "received_file" }
    }

    private fun createUniqueFile(fileName: String): File {
        if (!receiveDirectory.exists() && !receiveDirectory.mkdirs()) {
            throw IOException("Cannot create receive directory.")
        }

        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""

        var candidate = File(receiveDirectory, fileName)
        var index = 1

        while (candidate.exists()) {
            candidate = File(
                receiveDirectory,
                "${base}_$index$extension"
            )
            index++
        }

        return candidate
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1_000L ->
                "$bytes B"

            bytes < 1_000_000L ->
                String.format("%.2f KB", bytes / 1_000.0)

            bytes < 1_000_000_000L ->
                String.format("%.2f MB", bytes / 1_000_000.0)

            else ->
                String.format("%.2f GB", bytes / 1_000_000_000.0)
        }
    }

    fun stop() {
        running = false

        try {
            activeClient?.close()
        } catch (_: Exception) {
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }
}