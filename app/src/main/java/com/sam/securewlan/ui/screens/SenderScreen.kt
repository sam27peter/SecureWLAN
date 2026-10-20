package com.sam.securewlan.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sam.securewlan.network.PairingDetails
import com.sam.securewlan.network.PairingQr
import com.sam.securewlan.network.TcpClient

data class SelectedFile(val uri: Uri, val name: String, val size: Long?, val mimeType: String?)

private fun getFileDetails(context: Context, uri: Uri): SelectedFile {
    var name = "Unknown file"
    var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return SelectedFile(uri, name, size, context.contentResolver.getType(uri))
}

private fun formatFileSize(bytes: Long?): String = when {
    bytes == null || bytes < 0 -> "Unknown"
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> String.format("%.2f KB", bytes / 1_000.0)
    bytes < 1_000_000_000L -> String.format("%.2f MB", bytes / 1_000_000.0)
    else -> String.format("%.2f GB", bytes / 1_000_000_000.0)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var selectedFile by remember { mutableStateOf<SelectedFile?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf<PairingDetails?>(null) }
    var connectionStatus by remember { mutableStateOf("Not connected") }
    var transferProgress by remember { mutableStateOf(0) }
    var isTransferring by remember { mutableStateOf(false) }

    val permissionName = "android.permission.ACCESS_LOCAL_NETWORK"
    var localNetworkGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkGranted = granted
        if (!granted) connectionStatus = "Local network permission denied."
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, permissionName) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(permissionName)
        }
    }

    val tcpClient = remember { TcpClient() }
    DisposableEffect(tcpClient) { onDispose { tcpClient.shutdown() } }

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { scanResult ->
        val contents = scanResult.contents
        if (contents.isNullOrBlank()) {
            connectionStatus = "QR scan cancelled."
        } else {
            val details = PairingQr.parse(contents)
            if (details == null) {
                pairing = null
                connectionStatus = "Invalid or outdated SecureWLAN QR. Scan the receiver's current QR."
            } else if (!localNetworkGranted) {
                connectionStatus = "Grant local network permission first."
                permissionLauncher.launch(permissionName)
            } else {
                pairing = details
                connectionStatus = "Secure pairing details accepted. Ready to send."
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                selectedFile = getFileDetails(context, uri)
                errorMessage = null
                transferProgress = 0
                connectionStatus = "File selected. Ready to send securely."
            } catch (_: Exception) {
                selectedFile = null
                errorMessage = "Unable to read the selected file."
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Send a file") }, navigationIcon = {
            OutlinedButton(onClick = onBack) { Text("Back") }
        })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Send an image or PDF", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
                modifier = Modifier.fillMaxWidth(), enabled = !isTransferring) {
                Text(if (selectedFile == null) "Choose Image or PDF" else "Change File")
            }
            selectedFile?.let { file ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Selected file", style = MaterialTheme.typography.titleMedium)
                        Text("Name: ${file.name}")
                        Text("Size: ${formatFileSize(file.size)}")
                        Text("Type: ${file.mimeType ?: "Unknown"}")
                    }
                }
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                if (!localNetworkGranted) {
                    connectionStatus = "Grant local network permission first."
                    permissionLauncher.launch(permissionName)
                } else {
                    val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the receiver's current SecureWLAN QR code")
                        .setBeepEnabled(false).setOrientationLocked(false)
                    qrScannerLauncher.launch(options)
                }
            }, enabled = !isTransferring, modifier = Modifier.fillMaxWidth()) {
                Text("Pair with Receiver (Scan QR)")
            }
            Text(if (pairing != null) "Receiver paired for this session." else "Scan the receiver QR before sending.")
            Button(onClick = {
                val file = selectedFile
                val destination = pairing
                if (file == null) connectionStatus = "Choose an image or PDF first."
                else if (destination == null) connectionStatus = "Scan the receiver QR code first."
                else if (!localNetworkGranted) {
                    connectionStatus = "Grant local network permission first."
                    permissionLauncher.launch(permissionName)
                } else {
                    isTransferring = true
                    transferProgress = 0
                    connectionStatus = "Starting authenticated encrypted transfer..."
                    tcpClient.sendFile(
                        context = context,
                        uri = file.uri,
                        host = destination.host,
                        port = destination.port,
                        token = destination.token.copyOf(),
                        onProgress = { progress -> mainHandler.post { transferProgress = progress } },
                        onResult = { result ->
                            mainHandler.post {
                                connectionStatus = result
                                isTransferring = false
                            }
                        }
                    )
                }
            }, enabled = selectedFile != null && pairing != null && !isTransferring, modifier = Modifier.fillMaxWidth()) {
                Text(if (isTransferring) "Sending..." else "Send File Securely")
            }
            if (isTransferring) {
                LinearProgressIndicator(progress = { transferProgress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Transfer progress: $transferProgress%")
            }
            Spacer(Modifier.height(2.dp))
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text(connectionStatus)
            Text("Uses authenticated ECDH key exchange and AES-256-GCM encrypted chunks.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
