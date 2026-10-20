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

Run the unit tests, contract checks, and Android lint without server credentials or Firebase configuration:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/contract.py check
./gradlew test lintDebug
```

## Shared API contract

The bot repository owns the HTTP and server-sent event (SSE) schemas. Android commits their verified assets and generated Kotlin code.
Builds never download contracts or run a generator. Public CI requires no server token, Firebase account, or signing account.

`contract/pin.json` records the source, independent contract version, source commit, payload checksums, and generator hashes.
`contract/assets/` contains `openapi.json`, three event JSON Schemas, `SHA256SUMS`, the contract changelog, and `source-commit.txt`.
Checksums cover every payload, including provenance and the changelog. The checksum file does not checksum itself.

The initial pin is an explicit **local export** of bot commit `21ded3ab865cb5d13580b26474c1c5a1b941c590`, with contract version `1.0.0`.
It is not a published release. After bot publishes `contract-v1.0.0`, switch this pin to that release before merging the consumer update.

### Generate Kotlin

`scripts/generate-contract.py` is the repository-owned Kotlin generator, version `1.0.0`. It uses Python 3.10+ standard libraries only.
Its transport template lives in `scripts/templates/ContractBoundary.kt`. Both source files have SHA-256 pins in the manifest.
The existing Kotlin `2.3.20`, kotlinx.serialization `1.8.0`, and coroutines `1.10.2` versions remain in `gradle/libs.versions.toml`.

OpenAPI Generator's Kotlin generator was evaluated at `7.26.0` against its [official feature table](https://openapi-generator.tech/docs/generators/kotlin/).
It marks null, arbitrary JSON, unions, `oneOf`, and `anyOf` unsupported. Its default transports also differ from this app's transport.
Templates alone do not provide documented support for those missing schema semantics.
The small schema-driven generator preserves nullable fields, typed unions, open JSON as `JsonElement`, and the existing injectable `HttpTransport`.
This choice requires maintaining the supported schema subset in this repository instead of adopting another networking stack.

The generator emits serializable wire types and every HTTP operation, including binary downloads and streaming request paths.
It projects the downloaded event schemas into OpenAPI components, rewrites local schema references, and generates event payloads with the same generator.
The deterministic projection is committed beside the Kotlin output.
Unknown schema keywords, unsupported compositions, unsupported media types, and model collisions fail generation.
String enums remain strings for response forward compatibility. Value constraints such as string lengths and numeric limits remain server validation rules.
Generated codecs enforce field types and required fields; they are not a replacement for server-side business validation.

`APIClient` invokes generated operations for every endpoint. It retains runtime URLs, bearer tokens, cancellation, attachment and OAuth timeouts,
bounded errors, and the `HttpURLConnection` implementation. A small SSE adapter handles framing and stream lifetime.
It decodes generated payloads before passing app models to callers. Heartbeats and unknown event names do not interrupt chat updates.
`OptionalField` distinguishes omitted request properties from explicit JSON null, including PATCH clears.

`model/Models.kt` contains application and cached presentation state, including optimistic delivery state and local skill IDs.
`network/ContractAdapters.kt` maps generated responses into that state. Domain serializers remain for cache and editor state, not HTTP or SSE decoding.
The outgoing attachment model aliases the generated request type. Push preferences remain app inputs mapped to generated requests.

Regenerate the current verified pin explicitly:

```bash
python3 scripts/contract.py generate
python3 scripts/contract.py check
./gradlew test
```

`check` verifies assets and generator hashes, regenerates into a temporary directory, and compares every output byte.
It rejects stale files, manual edits, missing generated files, and unexpected files. Never edit generated output directly.

### Update the contract

To download the first immutable release, verify every asset, regenerate, and run `./gradlew test`:

```bash
python3 scripts/contract.py update --release contract-v1.0.0
```

For a later release, replace `contract-v1.0.0` with its exact `contract-vX.Y.Z` tag.
The updater rejects draft or prerelease releases and tag/version mismatches. It verifies all checksums before generation.
Review `contract/assets/CHANGELOG.md`, the pin, generated changes, and test results before committing.
An unsupported schema fails before replacing the current assets. A failing test leaves the candidate diff available for review; do not commit it.

Before a release exists, update from an explicit bot export directory:

```bash
python3 scripts/contract.py update --local EXPORT_DIRECTORY
```

Replace `EXPORT_DIRECTORY` with the directory produced by bot's `bun src/openapi.ts --bundle EXPORT_DIRECTORY` command.
The directory must contain exactly the seven agreed assets. The local pin records provenance without claiming a release tag.
Do not pin a moving branch or add a submodule.

When changing the generator, update its explicit version in both Python scripts and refresh the pin with the updater.
Review regenerated operations, payload types, optional/null behavior, and generator tests together.

### Automated updates

`.github/workflows/update-contract.yml` runs weekly and supports manual dispatch. It selects the latest published, stable `contract-v*` release by publication time.
Unrelated bot releases are ignored. Before the first release, it reports the bootstrap state and still checks the current pin and runs tests.
Download, authorization, and parsing failures fail the job rather than appearing as an absent release.

The updater runs checksum verification, generator tests, byte-for-byte checks, `./gradlew test`, and Android lint before creating or updating a PR.
It uses only the repository's `GITHUB_TOKEN`, with write permissions scoped to the updater job. Ordinary CI has read-only permissions.
GitHub can suppress or require approval for follow-up runs created by `GITHUB_TOKEN`; the updater does not rely on those runs.
See [GitHub workflow trigger behavior](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow).

Repository policy must permit GitHub Actions to create pull requests. If policy blocks PR creation, an owner must enable that permission.
The workflow does not change repository or organization settings.

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
