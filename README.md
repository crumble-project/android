# Crumble for Android

Crumble for Android is a native Kotlin and Jetpack Compose client for a running Crumble server. It provides agent management, one continuous chat per agent with streaming replies and file attachments, memory, skills, scheduled tasks, activity, integrations, and settings.

## Requirements

- Android 8.0 (API 26) or later.
- JDK 17+ (or Homebrew OpenJDK with Gradle toolchain resolution).
- Android SDK 36.
- A running Crumble server. Start the server from the sibling `bot` repository.

## Build and test

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

## Connect to a server

1. Start the server from the `bot` repository.
2. Copy the token from the server's `server-token` file.
3. Open the navigation drawer and tap **Settings** (gear icon).
4. Enter the server URL and token:
   - On the Android Emulator, use `http://10.0.2.2:7337` to reach a server running on `127.0.0.1:7337` on your host computer.
   - On a physical device on the same network, use your host's LAN address (for example `http://192.168.1.x:7337`).
5. Tap **Save Connection**.

The app stores the server URL and token encrypted with an AES-GCM key in the Android Keystore under `dev.crumble.android`.
