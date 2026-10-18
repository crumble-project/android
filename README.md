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

## Push notifications (optional)

Android push uses Firebase Cloud Messaging (FCM). Firebase setup is deferred.
Without `app/google-services.json`, the app builds and keeps its existing local stream notifications.
No Firebase account or cloud configuration is required for that build.

To enable remote delivery later:

1. Register `dev.crumble.android` in a Firebase project with Cloud Messaging enabled.
2. Download the Android configuration to `app/google-services.json` in this checkout.
3. On the backend, set `CRUMBLE_FCM_SERVICE_ACCOUNT_PATH` to an authorized service-account JSON file for the same Firebase project.
4. Build the debug APK.
5. Coordinate installation on the review Pixel with the Memory task.
6. Grant Android notification permission when the existing startup prompt appears.

Client configuration contains project identifiers. Service-account credentials belong on the backend and must never enter the client bundle or source control.
See [Add Firebase to your Android project](https://firebase.google.com/docs/android/setup) and the backend's `docs/native-push.md`.

The authenticated backend contract uses these endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/push` | Discover server identity and provider configuration. |
| `PUT` | `/v1/push/devices/:installationId` | Register or replace a token. |
| `DELETE` | `/v1/push/devices/:installationId` | Remove a registration. |
| `PUT` | `/v1/push/devices/:installationId/presence` | Update foreground and visible-chat presence. |

Presence refreshes every 30 seconds while active and expires after 120 seconds.
WorkManager retries registration and removal failures. Old connection credentials remain encrypted locally until their registration is removed.
Disabling app notifications or the reply channel unregisters on the next app resume.

FCM data messages contain string fields `type`, `eventId`, `serverId`, `agentId`, `agentName`, `entryId`, `origin`, `preview`, and `createdAt`.
An absent `entryId` becomes an empty string.
When registration returns `remoteDelivery: true`, FCM owns notification posting. The activity stream continues updating the UI.
Missing Firebase configuration, a push API returning `404`, or an unconfigured backend provider retains local stream notifications.
Visible-chat replies and bot-origin replies remain quiet. Previews and channel sound keep the existing defaults.
A persisted journal suppresses duplicates across transports and process restarts. Notification taps validate the saved server and connection before opening a chat.

The client uses standard registration tokens for the backend's HTTP v1 `message.token` transport.
Do not enable `firebase_messaging_installation_id_enabled`. FID direct-send requires a coordinated backend migration.

Debug builds retain the application ID and default debug signing configuration.
Updating an installed app requires the same signing key. Do not uninstall or clear app data to work around a mismatch.
Release builds require release signing configuration.

FCM delivery is best effort. After setup, validate these cases on a device:

- Screen-off and background delivery.
- Delivery after ordinary process death.
- Duplicate suppression and visible-chat suppression.
- Permission or channel denial and re-enabling.
- Connection switches and token rotation.
- Cold and warm notification taps.

A force-stopped app must be reopened before delivery resumes. Delivery before the first device unlock is unsupported.
Stale presence can suppress a reply for up to 120 seconds after abrupt termination.
Local notifications require a live app process and backend connection. ADB reverse provides backend access only while the development tunnel is available.
