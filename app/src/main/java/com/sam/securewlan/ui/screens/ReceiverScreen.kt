
package com.sam.securewlan.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sam.securewlan.network.NetworkUtils
import com.sam.securewlan.network.TcpServer
import java.io.File
import java.io.IOException
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var ipAddress by remember { mutableStateOf("Not available") }
    var status by remember { mutableStateOf("Not connected") }
    var isListening by remember { mutableStateOf(false) }
    var receivedMessage by remember {
        mutableStateOf("No file received yet.")
    }

    var receivedFilePath by remember { mutableStateOf<String?>(null) }
    var receivedFileName by remember { mutableStateOf<String?>(null) }
    var receivedMimeType by remember {
        mutableStateOf("application/octet-stream")
    }

    // Android 17 local-network permission.
    val localNetworkPermission =
        "android.permission.ACCESS_LOCAL_NETWORK"

    var localNetworkGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 37 ||
                    ContextCompat.checkSelfPermission(
                        context,
                        localNetworkPermission
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        localNetworkGranted = granted
        status = if (granted) {
            "Permission granted. Tap Start Receiving."
        } else {
            "Local network permission denied."
        }
    }

    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                context,
                localNetworkPermission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(localNetworkPermission)
        }
    }

    // Android's system picker lets the user choose the destination.
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val destinationUri: Uri? = result.data?.data

        if (result.resultCode == Activity.RESULT_OK &&
            destinationUri != null
        ) {
            val sourcePath = receivedFilePath

            if (sourcePath == null) {
                status = "No received file is available to save."
            } else {
                try {
                    val sourceFile = File(sourcePath)

                    if (!sourceFile.exists() || !sourceFile.isFile) {
                        throw IOException(
                            "The received file could not be found."
                        )
                    }

                    val output = context.contentResolver.openOutputStream(
                        destinationUri,
                        "w"
                    ) ?: throw IOException(
                        "Could not open the destination."
                    )

                    output.use { destination ->
                        sourceFile.inputStream().use { source ->
                            source.copyTo(destination)
                        }
                    }

                    status = "File saved successfully."
                    Toast.makeText(
                        context,
                        "File saved successfully",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    status = "Save failed: ${e.message ?: "Unknown error"}"
                }
            }
        }
    }

    val server = remember {
        TcpServer(
            receiveDirectory = File(
                context.filesDir,
                "received_files"
            ),
            onStatus = { message ->
                mainHandler.post {
                    status = message
                    isListening = message.startsWith("Listening")
                }
            },
            onMessage = { message ->
                mainHandler.post {
                    receivedMessage = message

                    val pathLine = message.lineSequence()
                        .firstOrNull {
                            it.startsWith("Saved to: ")
                        }

                    val typeLine = message.lineSequence()
                        .firstOrNull {
                            it.startsWith("Type: ")
                        }

                    receivedFilePath = pathLine
                        ?.removePrefix("Saved to: ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }

                    receivedFileName = receivedFilePath?.let {
                        File(it).name
                    }

                    receivedMimeType = typeLine
                        ?.removePrefix("Type: ")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                }
            }
        )
    }

    DisposableEffect(server) {
        onDispose {
            server.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive a file") },
                navigationIcon = {
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Receive over Wi-Fi",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                "Keep this screen open while the other device sends a file."
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Receiver details",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text("IP address: $ipAddress")
                    Text("Port: ${TcpServer.PORT}")
                    Text("Status: $status")
                }
            }

            if (!localNetworkGranted) {
                Text(
                    "Local network permission is required.",
                    color = MaterialTheme.colorScheme.error
                )

                Button(
                    onClick = {
                        permissionLauncher.launch(localNetworkPermission)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Local Network Permission")
                }
            }

            Button(
                onClick = {
                    if (!localNetworkGranted) {
                        status = "Grant local network permission first."
                        permissionLauncher.launch(localNetworkPermission)
                    } else {
                        val address = NetworkUtils.getLocalIpv4Address()

                        if (address == null) {
                            ipAddress = "Not available"
                            status =
                                "Connect to Wi-Fi or a hotspot and try again."
                        } else {
                            ipAddress = address
                            status = "Starting server..."
                            server.start()
                        }
                    }
                },
                enabled = !isListening,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start Receiving")
            }

            OutlinedButton(
                onClick = {
                    server.stop()
                    isListening = false
                    status = "Stopping server..."
                },
                enabled = isListening,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop Receiving")
            }

            Text(
                "Received file details",
                style = MaterialTheme.typography.titleLarge
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = receivedMessage,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Button(
                onClick = {
                    val path = receivedFilePath
                    val name = receivedFileName

                    if (path == null || name == null) {
                        status = "Receive a file successfully first."
                    } else if (!File(path).exists()) {
                        status = "Received file no longer exists."
                    } else {
                        val intent = Intent(
                            Intent.ACTION_CREATE_DOCUMENT
                        ).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = receivedMimeType
                            putExtra(Intent.EXTRA_TITLE, name)
                        }

                        saveFileLauncher.launch(intent)
                    }
                },
                enabled = receivedFilePath != null &&
                        receivedFileName != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Received File to Phone")
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Files remain in the app's private storage until you save " +
                        "them to a location such as Downloads or Documents.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
