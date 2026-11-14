# File compatibility implementation plan

> Implement one task at a time. Review each deliverable before starting the next.

**Goal:** Restore existing Android file sends and transcript attachments against the current server.
**Architecture:** Keep generated wire types authoritative. Adapt files at the network boundary and retain upload progress with each pending send.
**Source:** `docs/designs/2026-11-14-file-compatibility.md`.

## Decisions

- Prioritize compatibility, not new server features.
- Leave notification behavior to the other agent.
- Preserve optimistic sends, drafts, retry request IDs, and server/chat isolation.

## Constraints

- No new dependencies or server modifications.
- Regression tests must fail before fixes and pass afterward.
- Run Python tests, contract check, Android tests, lint, and debug assembly.

## Out of scope

Library browsing, new picker types, notification migration, and unrelated cleanup.

## Review focus

- Partial upload failure preserves already uploaded IDs for retry.
- Late uploads after reconnection cannot send to the new connection or alter its draft.
- Deleted transcript files do not trigger downloads.
- Group file downloads use file IDs rather than entry indices.

## Files

- `scripts/generate-contract.py`, `scripts/templates/ContractBoundary.kt`: support upload headers and raw bodies.
- `scripts/test_contract.py`: generator regression coverage.
- `contract/assets/`, `app/src/main/java/dev/crumble/android/generated/`: refreshed server contract.
- `app/src/main/java/dev/crumble/android/network/APIClient.kt`, `ContractAdapters.kt`: upload, send, download, and transcript conversion.
- `app/src/main/java/dev/crumble/android/model/Models.kt`, `AppModel.kt`: file identity and send lifecycle.
- `app/src/main/java/dev/crumble/android/model/Attachments.kt`: pending raw file bytes.
- `app/src/main/java/dev/crumble/android/ui/ChatScreen.kt`: shared attachment rendering, including deleted-file placeholders.
- `app/src/test/java/dev/crumble/android/FileCompatibilityTests.kt`: new file lifecycle regressions.
- Existing tests in `GeneratedContractTests.kt`, `CrumbleTests.kt`, `DraftsAndReadsTests.kt`, and `GroupChatTests.kt`: update old wire expectations and fixtures.
- `README.md`, `CHANGELOG.md`: document compatibility behavior.

### Task 1: Upload-capable contract generation

**Interfaces:** `HttpRequestData` already carries `headers: Map<String, String>` and `body: ByteArray?` (`network/APIClient.kt:95`). Add an optional headers argument to generated `ContractBoundary.request` and `send`, forwarding it through the existing transport.

- [x] Add and demonstrate failing generator regressions for header parameters and binary bodies.
- [x] Support these constructs using the existing HTTP transport, then regenerate from `../bot/openapi/`.
- [x] Verify generated output and Python tests.
- [x] Resolve compile integration with removed presence operation without implementing notification behavior.

### Task 2: File-aware sends and transcript attachments

**Interfaces:** `PendingAttachment` holds name, MIME type, raw bytes, and a local UUID (`model/Attachments.kt:22`). `PendingSend` holds immutable content, reply target, and attachments (`model/AppModel.kt:412`); add per-send successful upload IDs. Keep `sendChatMessage` and `sendGroupMessage` callers explicit: send file IDs after uploads. `AttachmentInfo` (`model/Models.kt:173`) gains file ID and deletion state. Preserve entry/index UI loader signatures; resolve current transcript metadata before fetching by file ID.

- [x] Add and demonstrate failing regressions for uploads, file-ID sends, partial retry, deleted files, group downloads, and connection changes.
- [x] Add file metadata conversion and content fetching by ID.
- [x] Upload before sending while retaining successful uploads for retry within the originating connection and chat.
- [x] Preserve existing optimistic state and failure recovery.
- [x] Run targeted tests, then all project checks.
- [x] Update README and changelog.

## Deviations

- Task 1 · planned: resolve removed presence operation without notification behavior changes · did: retain the identical legacy presence request at the hand-written HTTP boundary · why: generated operations must match the current server; notification migration remains owned by another agent. The legacy presence route remains incompatible until that work lands.
- Task 1 · planned: refresh the existing export set · did: also copy and generate the group snapshot event export · why: spec review found otherwise-unverified group event file schemas.
- Task 2 · planned: preserve existing failure behavior · did: retain group attachments on the failed row without adding composer restoration · why: restoring a group composer would require a separate duplicate-send policy.
- Task 2 · planned: retain successful upload IDs · did: clear them only after a definitive missing/deleted-file rejection, retaining the message request ID · why: otherwise deleted uploads prevent recovery.
- Task 2 · planned: update attachment tests · did: also update wire fixtures in `CallRequestTests.kt`, `ChatParityTests.kt`, and `SecretsTests.kt` · why: the refreshed server contract requires `replyReferences` on every transcript message.

## Verification and review

- Four initial file compatibility regressions failed against the old client and pass after implementation.
- Three generator compatibility regressions failed before generator changes and pass afterward.
- Review found deletion across retained references was incomplete. Two added bot regressions failed before correction; bot and group regressions now pass.
- Python tests: 12 passed. Android unit tests: 492 passed, no failures or skips.
- Generated-output verification, asset comparison with `../bot/openapi/`, `git diff --check`, lint, and debug assembly passed.
- The first full Android run failed on stale transcript fixtures and exhausted memory in a polling test. Updating required `replyReferences` fields resolved both failures without changing runtime behavior.
- Independent implementation review and focused correction review completed with no remaining findings.
- Live server and device or emulator behavior were not tested. Notification compatibility was deferred in this phase. The owner subsequently approved [Android chat attention](2026-11-14-chat-attention.md) in the same PR, replacing the legacy presence boundary.

Keep one implementer for the send lifecycle because upload progress and optimistic reconciliation share state. The owner authorized committing, pushing, and opening PR #17 after verification.
