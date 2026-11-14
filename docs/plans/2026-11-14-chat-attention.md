# Android chat attention implementation plan

> Implement one task at a time. Review each deliverable before starting the next.

**Goal:** Report native chat attention and acknowledge exact displayed reply surfaces using the current server contract.
**Architecture:** A coroutine controller serializes independent view presence and retries per-chat seen batches. Compose supplies mounted-view and surface visibility evidence; push registration remains separate.
**Source:** `docs/designs/2026-11-14-chat-attention.md`.

## Decisions

- Include notification adoption in PR #17 after the server implementation finished.
- Preserve push delivery, registration, notification preferences, and taps.
- Fail closed for uncertain surface readiness; never infer seen state from event receipt or history fetches.

## Constraints

- Reuse generated contract operations and types. No new dependencies.
- No simulator or emulator tests in CI. Default tests use recording transports, never real providers.
- Run Python tests, contract checks, Android unit tests, lint, and debug assembly.

## Out of scope

Server changes, notification preferences, unread badge synchronization, provider behavior, and unsupported new card interfaces.

## Review focus

- Active refresh already in flight when the app pauses must be followed by a clear, never by another stale refresh.
- A delayed rendering callback cannot cross account, server, or chat identity.
- A failed submitted seen batch retains its original chat when navigation changes.
- Loading, collapsed, offscreen, and error card states must not count as seen.

## Files

- `push/ChatAttention.kt` (new): controller and exact-surface matching helpers.
- `network/APIClient.kt`, `push/PushContract.kt`: new attention operations, removal of legacy presence contract.
- `model/Models.kt`, `network/ContractAdapters.kt`: reply references on domain messages.
- `model/ReplyAttention.kt` (new): exact-surface identity, route identity, and canonical card readiness.
- `MainActivity.kt`, `push/PushRuntime.kt`, `push/PushSyncWorker.kt`: independent attention lifecycle and removal of registration-dependent reporting.
- `ui/ReplyAttention.kt` (new), `ui/ChatScreen.kt`, `ui/GroupChatScreen.kt`: viewport and mounted-chat reporting.
- `ui/DecisionCards.kt` supplies existing readiness states; `model/Secrets.kt` supplies `secretRequestState`. Add a shared model readiness helper used at the real card dispatch boundaries, not at rows.
- `model/AppModel.kt`: refresh the active matching snapshot after live reply events.
- `ChatAttentionTests.kt`, `ReplyAttentionTests.kt` (new), existing push contract and chat fixtures: regression coverage.
- `README.md`, `CHANGELOG.md`, earlier compatibility docs: replace the deferred-notification limitation with implemented behavior.

### Task 1: Contract and controller

**Interfaces:** add `APIClient.putChatAttentionView(viewID: String, chat: ChatAttentionChat?)` and `postChatAttentionSeen(chat: ChatAttentionChat, eventIDs: List<String>)`. Create `ChatAttentionChat(kind: String, id: String)`. `ChatAttention(scope: CoroutineScope)` exposes `val viewId: String`, `update(api: APIClient?, active: Boolean, chat: ChatAttentionChat?)`, `setDisplayedChat(chat: ChatAttentionChat?)`, `newView()`, `displayed(api: APIClient, viewId: String, chat: ChatAttentionChat, eventIDs: List<String>)`, and `suspend disconnect(api: APIClient)`. Disconnect uses a bounded best-effort old-API clear and invalidates old seen work. New views retain seen batches within the same connection. Domain messages retain generated `TranscriptMessageReplyReferencesItem` values; SSE adapters convert their structurally identical references to that canonical type.

- [x] Add regressions and demonstrate failures before implementation.
- [x] Implement serialized presence, heartbeat, view rotation, and scoped acknowledgement batching/retry.
- [x] Preserve generated reply references in domain messages.
- [x] Verify controller and API regressions.

### Task 2: Native view and renderer integration

**Interfaces:** create `AttentionChat(model, chat, ready, content)` as the mounted-view provider, `attentionViewport(): Modifier` for the real list bounds, and `ReplyAttention(model, message, surface, ready, content)` around exact rendered content. Surface matching uses canonical generated references and original API/view/chat identity. Readiness follows the specification's Ready surfaces section and existing card state helpers. The activity updates foreground/route/API independently from Compose mounting. The retained model clears attention before old-session revocation.

- [x] Connect native foreground state and mounted chat presence independently of push registration.
- [x] Report only exact ready visible surfaces, with stale-callback identity checks.
- [x] Refresh the matching active snapshot after live replies without replacing optimistic state.
- [x] Remove legacy presence reporting and update its tests.
- [x] Update documentation and run all project checks.
- [x] Review against the specification in a fresh reviewer context.

## Deviations

- Task 1 · Bound every retired view's clear to two seconds and track its connection ownership. Review found abandoned view retries could otherwise outlive logout.
- Task 1 · Reject a disconnected API identity across null updates until a distinct connection is installed. Review found Activity recreation could otherwise reconnect during sign-out.
- Task 2 · Use an attention modifier directly on Markdown text, excluding citation-only visibility. Card content uses the specified wrapper.
- Task 2 · Keep an Activity window token separate from the connection-rotated server view ID. Old Activity callbacks cannot mount or report for a new window.
- Task 2 · Make `appActive` observable Compose state. Review found unchanged geometry would otherwise prevent acknowledgement after resuming.
- Task 2 · Question cards remain without an acknowledgement boundary because the server introduces no standalone question producer. Existing ordinary referenced text remains supported.

## Verification

- The old presence endpoint assertion failed before migration and passes through the new independent API.
- Both teardown regressions failed before the fixes and pass afterward. The foreground-observation regression also failed before its fix.
- Controller tests cover mount/foreground gates, bot/group identity, serialized clear/heartbeat, view rotation, scoped retries, 100-ID batching, invalid batches, disconnection, and authentication failure.
- Renderer policy tests cover exact surface identity, viewport intersection, secret-card readiness, preserved HTTP/event references, and refresh ordering across stream updates and navigation.
- Python tests: 12 passed. Android unit tests: 508 passed, no failures or skips. Contract verification, lint, debug assembly, and `git diff --check` passed.
- Fresh implementation review and focused correction review completed with no remaining findings.
- Native viewport/lifecycle behavior was not manually tested on a device or emulator. Live providers and cross-client delivery were not tested.
- Previous PR CI passed, but its unit-test job took 2m23s, above the repository's two-minute target. CI configuration was not changed by notification adoption; the next run's duration remains unverified.
