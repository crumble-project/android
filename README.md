# Crumble for Android

Native Kotlin and Jetpack Compose client for a Crumble server.

## Requirements

- Android 8.0 (API 26)+, Android SDK 36, and JDK 17+.
- A running Crumble server from the sibling `bot` repository.

## Build and test

```bash
./gradlew assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/contract.py check
./gradlew test lintDebug
```

## Connect to a server

Open the navigation drawer › **Settings**, enter the server URL (`http://10.0.2.2:7337` on the Android Emulator for a host server on `127.0.0.1:7337`, or a LAN/HTTPS address on a physical device) and `server-token`, and tap **Save Connection**. Credentials are stored encrypted in the Android Keystore.

## Shared API contract

`contract/pin.json` and `contract/assets/` pin the verified `bot` contract release (`contract-vX.Y.Z`). `scripts/generate-contract.py` generates Kotlin wire models and operations without external build-time dependencies.

```bash
python3 scripts/contract.py generate                        # Regenerate from current pin
python3 scripts/contract.py check                           # Verify checksums and byte-for-byte output
python3 scripts/contract.py update --release contract-vX.Y.Z # Update to a published release
python3 scripts/contract.py update --local EXPORT_DIR       # Update from a local bot bundle
```

## Push notifications (optional)

Without `app/google-services.json`, the app builds cleanly and posts local notifications while connected to `/v1/events`. To enable background delivery via Firebase Cloud Messaging (FCM), place `google-services.json` in `app/` for `dev.crumble.android` and set `CRUMBLE_FCM_SERVICE_ACCOUNT_PATH` on the server (see `bot/docs/native-push.md`).
