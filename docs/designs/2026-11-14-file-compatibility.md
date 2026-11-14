# File compatibility with the current server

## Goal

Restore existing Android attachment behavior against the current server contract in `../bot/openapi/`. Do not add new product surfaces.

## Scope

- Extend contract generation and its transport boundary to support upload headers, raw binary requests, and binary download responses. Explicit MIME headers override the JSON default.
- Include the group snapshot export in contract copying and generation so group event files are checked too.
- Refresh contract assets and generated Kotlin models and operations from the server's committed export.
- Upload bot and group chat attachments before sending messages. Submit returned file IDs through `files`, not inline `attachments`.
- Read transcript file metadata and deleted-file markers. Fetch attachment contents by file ID for bot and group chats.
- Preserve existing image previews, drafts, reply targets, optimistic sends, and duplicate-free retries.
- Update documentation, changelog, and regression coverage.

## Behavior

A send displays its optimistic message immediately. Bot upload failures preserve the draft and attachments through the existing restoration behavior. Group failures retain attachments and reply identity on the failed row, retried through its Retry action; do not add group composer restoration. Retries reuse the message request ID. Reuse successfully uploaded files when retrying that send within the same server and chat; never share uploaded IDs across chats or connections.

If the server definitively rejects a send because a cached file was deleted or is missing from its library, discard that send's upload IDs. The next explicit retry uploads from retained local bytes, retaining the message request ID. Ambiguous failures keep cached IDs.

Late uploads or sends from a previous connection must not alter the current connection's state. Navigating between chats must not lose drafts or route attachments to the wrong chat. Server refreshes must not overwrite pending sends. Reconciliation must not duplicate messages.

Transcript file IDs are authoritative for content requests and cache identity, scoped by connection and chat. Derive image preview eligibility from MIME metadata. Deleted files remain recognizable without content fetches or crashes. Deleted metadata hides cached images immediately. Downloads completing after deletion must not republish their bytes.

## Notification boundary

Notification behavior belongs to another agent. Do not implement chat attention, alter notification suppression, or modify push registration/presence lifecycle code. Generated contract changes include server notification declarations mechanically. If removed operations block compilation, coordinate an integration boundary without preserving obsolete generated endpoints or inventing replacement behavior.

## Out of scope

Library browsing, manual file management, new attachment picker types, notification migration, server changes, and unrelated client features.

## Verification

Demonstrate failing regressions before fixing compatibility. Cover upload headers and bytes, file IDs in sends, partial upload failure and retry, deleted files, group downloads, and connection changes during upload. Run Python generator tests, generated-output verification, Android unit tests, lint, and the debug build. No real server credentials or paid services in tests. Report notification integration blockers separately.
