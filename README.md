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

Open the navigation drawer › **Settings**, enter the server URL (`http://10.0.2.2:7337` on the Android Emulator for a host server on `127.0.0.1:7337`, or a LAN/HTTPS address on a physical device), your account email and password, and tap **Save Connection**. The app stores the account session encrypted in the Android Keystore; it does not store the password. Debug builds install as **Crumble Dev** (`ng.crumbli.android.dev`) beside the release app (`ng.crumbli.android`), so each keeps its own connection; the sign-in callback scheme is the application ID, for example `ng.crumbli.android:/oauth/callback`.

## Shared API contract

`contract/pin.json` and `contract/assets/` pin a verified `bot` contract: a local export of a `bot` commit, or a release (`contract-vX.Y.Z`). `bot` no longer publishes contract releases, so current updates use `--local`. `scripts/generate-contract.py` generates Kotlin wire models and operations without external build-time dependencies.

```bash
python3 scripts/contract.py generate                        # Regenerate from current pin
python3 scripts/contract.py check                           # Verify checksums and byte-for-byte output
python3 scripts/contract.py update --release contract-vX.Y.Z # Update to a published release
python3 scripts/contract.py update --local EXPORT_DIR       # Update from a local bot bundle
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

## License

Licensed under the [GNU Affero General Public License v3.0](LICENSE) (`AGPL-3.0-only`). Third-party components retain their respective licenses.
