# Crumble for Android

Native Kotlin and Jetpack Compose client for a Crumble server.

## Requirements

- Android 8.0 (API 26)+, Android SDK 36, and JDK 17+.
- A running Crumble server from the sibling `bot` repository.

## Build and test

Gradle builds bot avatars from the [open-bot-avatar-kt](https://github.com/cheetahbyte/open-bot-avatar-kt) `0.1.0` tag on GitHub. Set `ANDROID_HOME` in your environment before building, because `local.properties` does not reach that source build.

```bash
./gradlew assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/contract.py check
./gradlew test lintDebug
```

## Connect to a server

Open the navigation drawer › **Settings**, enter the server URL (`http://10.0.2.2:7337` on the Android Emulator for a host server on `127.0.0.1:7337`, or a LAN/HTTPS address on a physical device), your account email and password, and tap **Save Connection**. The app stores the account session encrypted in the Android Keystore; it does not store the password. Debug builds install as **Crumble Dev** (`ng.crumbli.android.dev`) beside the release app (`ng.crumbli.android`), so each keeps its own connection.

The app doesn't configure the server or the account. Providers, credentials, logins, password managers, integrations, coding, users, message gateways, and the event relay are set in the web app: **Settings** › **Server settings** opens it in the browser.

## Chat files

Bot and group chats upload attachments as raw bytes, then send their file IDs with the message. Failed sends retain attachments for retry. Retries reuse confirmed uploads and the message request ID. Deleted transcript files display **File deleted** instead of an image.

Library browsing is not included.

## Shared API contract

`contract/assets/` holds the HTTP contract and bot, group, activity, and heartbeat event schemas from `bot/openapi/`. A running server also serves its document at `GET /openapi.json`. `scripts/generate-contract.py` generates Kotlin wire models and operations without external build-time dependencies, including upload headers and binary bodies.

```bash
python3 scripts/contract.py generate                         # Regenerate from contract/assets/
python3 scripts/contract.py check                            # Verify the generated output byte-for-byte
python3 scripts/contract.py update --from ../bot/openapi     # Copy the contract files, regenerate, and run ./gradlew test
```

## Release

Pushing a `vMAJOR.MINOR.PATCH` tag runs `.github/workflows/release.yml`, which builds a signed release APK with FCM and attaches it to a GitHub release. The version code is `MAJOR * 10000 + MINOR * 100 + PATCH`, so minor and patch stay below 100.

The workflow reads three repository secrets: `ANDROID_KEYSTORE_BASE64` (the base64 of the release keystore), `ANDROID_KEYSTORE_PASSWORD`, and `GOOGLE_SERVICES_JSON_BASE64`. Every update must be signed with the same keystore, alias `crumble`, so keep a backup of it.

To sign a release build locally:

```bash
CRUMBLE_KEYSTORE_PATH=~/crumble-release.jks CRUMBLE_KEYSTORE_PASSWORD=... ./gradlew assembleRelease
```

## Push notifications (optional)

Without `app/google-services.json`, the app builds cleanly and posts local notifications while connected to `/v1/events`. To enable background delivery via Firebase Cloud Messaging (FCM), place `google-services.json` registering `ng.crumbli.android` and `ng.crumbli.android.dev` in `app/` and set `CRUMBLE_FCM_SERVICE_ACCOUNT_PATH` on the server (see `bot/docs/native-push.md`).

The app reports foreground bot and group chats through the chat-attention API, independently of notification permission and FCM registration. The server uses these reports to suppress account-wide pushes and waits at least three seconds before delivery.

Seen acknowledgements identify the exact reply text or loaded request card visible in the chat viewport. Loading states, collapsed summaries, offscreen content, and unsupported group cards do not count as seen. Acknowledgement retries retain their original chat; account changes discard the old account's pending work.

## License

Licensed under the [GNU Affero General Public License v3.0](LICENSE) (`AGPL-3.0-only`). Third-party components retain their respective licenses.
