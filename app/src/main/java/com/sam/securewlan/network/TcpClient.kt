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
import java.util.concurrent.Executors

class TcpClient {

    companion object {
        private const val MAGIC = 0x53574631 // "SWF1"
        private const val MAX_FILE_SIZE = 500L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
    }

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Retained for the existing connection-test button.
     */
    fun testConnection(
        host: String,
        port: Int = TcpServer.PORT,
        onResult: (String) -> Unit
    ) {
        executor.execute {
            val result = try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host, port),
                        5000
                    )
                    socket.soTimeout = 5000

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
     * Sends the selected document using a binary-safe protocol.
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
                var fileSize: Long? = null

                resolver.query(
                    uri,
                    arrayOf(
                        OpenableColumns.DISPLAY_NAME,
                        OpenableColumns.SIZE
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex =
                            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                        val sizeIndex =
                            cursor.getColumnIndex(OpenableColumns.SIZE)

                        if (nameIndex >= 0) {
                            fileName =
                                cursor.getString(nameIndex) ?: fileName
                        }

                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                }

                val mimeType =
                    resolver.getType(uri) ?: "application/octet-stream"

                // Some document providers don't report the file size.
                // Stage those files to cache so the protocol has an exact size.
                if (fileSize == null || fileSize!! < 0) {
                    temporaryFile = File.createTempFile(
                        "securewlan_",
                        ".tmp",
                        context.cacheDir
                    )

                    resolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temporaryFile!!).use { output ->
                            input.copyTo(output, BUFFER_SIZE)
                        }
                    } ?: throw IOException("Unable to open selected file.")

                    fileSize = temporaryFile!!.length()
                }

                val totalBytes = fileSize!!

                if (totalBytes > MAX_FILE_SIZE) {
                    throw IOException("Maximum supported file size is 500 MB.")
                }

                if (totalBytes == 0L) {
                    throw IOException("The selected file is empty.")
                }

                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(host.trim(), port),
                        10_000
                    )
                    socket.soTimeout = 120_000
                    socket.tcpNoDelay = true

                    val output = DataOutputStream(
                        BufferedOutputStream(
                            socket.getOutputStream()
                        )
                    )

                    val input = DataInputStream(
                        BufferedInputStream(
                            socket.getInputStream()
                        )
                    )

                    // Header: magic, filename, MIME type, byte count.
                    output.writeInt(MAGIC)
                    output.writeUTF(fileName.take(200))
                    output.writeUTF(mimeType.take(200))
                    output.writeLong(totalBytes)

                    var sentBytes = 0L
                    val buffer = ByteArray(BUFFER_SIZE)

                    val fileInput = if (temporaryFile != null) {
                        FileInputStream(temporaryFile!!)
                    } else {
                        resolver.openInputStream(uri)
                            ?: throw IOException(
                                "Unable to reopen selected file."
                            )
                    }

                    fileInput.use { source ->
                        while (sentBytes < totalBytes) {
                            val wanted = minOf(
                                buffer.size.toLong(),
                                totalBytes - sentBytes
                            ).toInt()

                            val count = source.read(buffer, 0, wanted)

                            if (count == -1) {
                                throw IOException(
                                    "Source file ended unexpectedly."
                                )
                            }

                            output.write(buffer, 0, count)
                            sentBytes += count

                            onProgress(
                                ((sentBytes * 100) / totalBytes).toInt()
                            )
                        }
                    }

                    output.flush()

                    // The receiver acknowledges only after saving the file.
                    val success = input.readBoolean()
                    val message = input.readUTF()

                    if (!success) {
                        throw IOException(message)
                    }

                    "Transfer successful!\n$message"
                }
            } catch (e: Exception) {
                "Transfer failed: ${e.message ?: "Unknown error"}"
            } finally {
                try {
                    temporaryFile?.delete()
                } catch (_: Exception) {
                }
            }

            onResult(result)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}