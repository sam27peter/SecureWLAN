# SecureWLAN

SecureWLAN is an Android application for securely transferring files between devices connected to the same Wi-Fi network.

The application provides separate sender and receiver workflows. One device selects a file and sends it through a local TCP connection, while another device receives the file. The project also includes QR-code-based pairing and encryption components intended to protect files during transfer.

## Features

- Secure file sharing over a local Wi-Fi network
- Separate sender and receiver screens
- TCP client/server communication
- QR-code pairing support
- File encryption before transfer
- Android Jetpack Compose user interface
- Material 3 design components
- Support for Android devices running API 26 or higher
- Camera access for QR-code scanning
- Local-network and Wi-Fi state permissions

## What Has Been Done

The project implements the foundation of a secure peer-to-peer file-sharing application for Android.

The main work completed includes:

- Created an Android application named `SecureWLAN`
- Built the user interface with Kotlin and Jetpack Compose
- Added a home screen with:
  - Send File option
  - Receive File option
  - Wi-Fi connection guidance
- Added separate sender and receiver screens
- Added TCP networking components for local file transfer
- Added QR-code support for pairing devices
- Added encryption-related classes for protecting files and transfers
- Added Android permissions for:
  - Internet access
  - Local-network access
  - Wi-Fi state access
  - Network state access
  - Camera access
- Configured Gradle version catalogs and the Android build system
- Added unit-test and Android instrumentation-test source sets
- Added Android backup and data-extraction configuration files

## Technology Stack

### Programming Language

- Kotlin

### Platform

- Android

### User Interface

- Jetpack Compose
- Material 3
- AndroidX Activity Compose
- AndroidX Core KTX

### Build System

- Gradle Kotlin DSL
- Android Gradle Plugin
- Gradle Version Catalogs
- Kotlin Compose compiler plugin

### Networking

- TCP client/server communication
- Local Wi-Fi network communication
- Network utility functions for detecting and working with local network details

### QR-Code Support

- ZXing Android Embedded `4.3.0`

### Testing

- JUnit
- AndroidX JUnit
- Espresso
- Compose UI testing

## Encryption and Security

SecureWLAN includes dedicated security and cryptography components in the following files:

- `FileEncryption.kt`
- `SecureTransferCrypto.kt`

These components are responsible for the encryption-related parts of the application, including protecting files and securing data during transfer.

The application also uses a pairing workflow based on QR codes. This allows devices to exchange or scan connection information more easily before transferring a file.

The Android manifest includes the following security-related permissions:

- `INTERNET`
- `ACCESS_LOCAL_NETWORK`
- `ACCESS_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CAMERA`

### Important Security Note

SecureWLAN is designed for secure file transfer over a trusted local network. Before using it for sensitive data in production, the encryption implementation should be reviewed and tested thoroughly.

In particular, verify:

- The exact encryption algorithm and mode
- Key generation and exchange
- Initialization-vector or nonce handling
- Authentication and integrity verification
- Protection against replay attacks
- Secure deletion of temporary files
- Validation of received files
- Error handling when a transfer is interrupted

## Project Structure

```text
SecureWLAN/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/
│       │   │   └── com/
│       │   │       └── sam/
│       │   │           └── securewlan/
│       │   │               ├── MainActivity.kt
│       │   │               ├── network/
│       │   │               │   ├── NetworkUtils.kt
│       │   │               │   ├── PairingQr.kt
│       │   │               │   ├── SecureTransferCrypto.kt
│       │   │               │   ├── TcpClient.kt
│       │   │               │   └── TcpServer.kt
│       │   │               ├── security/
│       │   │               │   └── FileEncryption.kt
│       │   │               └── ui/
│       │   │                   ├── screens/
│       │   │                   │   └── SenderScreen.kt
│       │   │                   │   └── ReceiverScreen.kt
│       │   │                   └── theme/
│       │   │                       ├── Color.kt
│       │   │                       ├── Theme.kt
│       │   │                       └── Type.kt
│       │   ├── keepRules/
│       │   └── res/
│       │       ├── drawable/
│       │       ├── mipmap-anydpi/
│       │       ├── mipmap-hdpi/
│       │       ├── mipmap-mdpi/
│       │       ├── mipmap-xhdpi/
│       │       ├── mipmap-xxhdpi/
│       │       ├── mipmap-xxxhdpi/
│       │       ├── values/
│       │       │   ├── colors.xml
│       │       │   ├── strings.xml
│       │       │   └── themes.xml
│       │       └── xml/
│       │           ├── backup_rules.xml
│       │           └── data_extraction_rules.xml
│       ├── test/
│       │   └── java/
│       │       └── com/
│       │           └── sam/
│       │               └── securewlan/
│       └── androidTest/
│           └── java/
│               └── com/
│                   └── sam/
│                       └── securewlan/
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── settings.gradle.kts
```

## Main Components

### `MainActivity.kt`

The main Android activity starts the application and configures the Compose UI.

It also controls navigation between:

- The home screen
- The sender screen
- The receiver screen

### `network/`

The `network` package contains the communication and transfer-related functionality.

#### `NetworkUtils.kt`

Provides network-related helper functionality, such as identifying local network information.

#### `PairingQr.kt`

Contains QR-code pairing functionality used to help connect two devices.

#### `TcpClient.kt`

Handles the client side of the TCP file-transfer connection.

#### `TcpServer.kt`

Handles the server side of the TCP file-transfer connection.

#### `SecureTransferCrypto.kt`

Contains cryptographic functionality related to securing the transfer process.

### `security/`

#### `FileEncryption.kt`

Contains file-encryption functionality used to protect files before or during transfer.

### `ui/screens/`

Contains the application's main screens:

- `SenderScreen.kt`
- `ReceiverScreen.kt`

### `ui/theme/`

Contains the Compose theme configuration, including colors, typography, and application theme settings.

### `res/`

Contains Android resources such as:

- Application strings
- Colors
- Themes
- Launcher icons
- Backup rules
- Data-extraction rules

## System Requirements

To build and run SecureWLAN, you need:

- Android Studio
- Android SDK
- JDK 11 or a compatible Android Studio JDK
- Gradle support through the included Gradle wrapper
- An Android device or emulator running Android API 26 or higher

The project configuration currently uses:

- Minimum SDK: API 26
- Target SDK: API 37
- Compile SDK: API 37
- Java compatibility: Java 11
- Kotlin: `2.2.10`
- Android Gradle Plugin: `9.4.1`

## Download the Project

### Clone with Git

```bash
git clone https://github.com/sam27peter/SecureWLAN.git
cd SecureWLAN
```

### Download as a ZIP file

1. Open the repository on GitHub:

   https://github.com/sam27peter/SecureWLAN

2. Select **Code**.
3. Select **Download ZIP**.
4. Extract the downloaded archive.
5. Open the extracted project in Android Studio.

## Build the Application

### Linux or macOS

```bash
./gradlew assembleDebug
```

### Windows

```bat
gradlew.bat assembleDebug
```

The generated debug APK should be available under:

```text
app/build/outputs/apk/debug/
```

## Run the Application

1. Open the project in Android Studio.
2. Allow Gradle to synchronize the project.
3. Connect an Android device or start an emulator.
4. Ensure the device runs Android API 26 or higher.
5. Press the **Run** button in Android Studio.

You can also install the debug APK manually:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

The exact APK filename may depend on the Gradle build configuration.

## How to Use SecureWLAN

### On the sending device

1. Connect the sending device to a Wi-Fi network.
2. Open SecureWLAN.
3. Select **Send File**.
4. Choose the file that you want to transfer.
5. Use the pairing or QR-code workflow when prompted.
6. Wait for the receiving device to connect.
7. Start the secure transfer.

### On the receiving device

1. Connect the receiving device to the same Wi-Fi network.
2. Open SecureWLAN.
3. Select **Receive File**.
4. Scan the pairing QR code or enter the required connection information.
5. Accept the incoming transfer.
6. Wait for the file transfer to complete.

Both devices must be connected to the same local Wi-Fi network for the TCP connection to work.

## Required Permissions

SecureWLAN requests the following Android permissions:

| Permission | Purpose |
|---|---|
| `INTERNET` | Enables network communication |
| `ACCESS_LOCAL_NETWORK` | Allows communication over the local network |
| `ACCESS_NETWORK_STATE` | Checks network connectivity |
| `ACCESS_WIFI_STATE` | Reads Wi-Fi connection information |
| `CAMERA` | Scans QR codes for device pairing |

Some permissions may require approval from the user while the application is running.

## Testing

Run local unit tests with:

```bash
./gradlew test
```

Run Android instrumentation tests with:

```bash
./gradlew connectedAndroidTest
```

On Windows, use:

```bat
gradlew.bat test
gradlew.bat connectedAndroidTest
```

Instrumentation tests require a connected Android device or running emulator.

## Application Flow

```text
User opens the app
        |
        v
    Home Screen
     /       \
    /         \
Send File   Receive File
    |             |
    v             v
TCP Client    TCP Server
    |             |
    +------ Pairing ------+
              |
              v
       Encrypted Transfer
```

The application uses the sender and receiver roles to establish a local TCP connection. Pairing information is exchanged through the QR-code workflow, and the encryption components are used to protect transfer data.

## Current Limitations

- Both devices must be connected to the same Wi-Fi network.
- The application depends on local-network connectivity.
- Firewall or router settings may block device-to-device communication.
- Android permission approval is required for network and camera functionality.
- A production release should include additional security testing and validation.
- The project currently focuses on the Android application and does not include a separate desktop or web client.

## License

No license is currently specified for this repository.

If you plan to distribute or reuse the project, add an appropriate license file such as:

- MIT License
- Apache License 2.0
- GNU General Public License v3.0

## Project Information

- **Project:** SecureWLAN
- **Package:** `com.sam.securewlan`
- **Platform:** Android
- **Primary language:** Kotlin
- **Repository:** https://github.com/sam27peter/SecureWLAN
