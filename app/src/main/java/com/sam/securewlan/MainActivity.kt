
package com.sam.securewlan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sam.securewlan.ui.screens.SenderScreen
import com.sam.securewlan.ui.theme.SecureWLANTheme
import com.sam.securewlan.ui.screens.ReceiverScreen
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SecureWLANTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    var currentScreen by remember {
        mutableStateOf("home")
    }

    when (currentScreen) {
        "sender" -> {
            SenderScreen(
                onBack = {
                    currentScreen = "home"
                }
            )
        }

        "receiver" -> {
            ReceiverScreen(
                onBack = {
                    currentScreen = "home"
                }
            )
        }

        else -> {
            HomeScreen(
                onSend = {
                    currentScreen = "sender"
                },
                onReceive = {
                    currentScreen = "receiver"
                }
            )
        }
    }
}

@Composable
fun HomeScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "SecureWLAN",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Secure file sharing over Wi-Fi",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Send a file",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Select a file and prepare to share it securely."
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onSend,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send File")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Receive a file",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Prepare this device to receive files " +
                            "from another device."
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onReceive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Receive File")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Connect both devices to the same Wi-Fi network.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}
