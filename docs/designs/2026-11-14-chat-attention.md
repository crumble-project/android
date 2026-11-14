# Android chat attention

## Goal

Adopt the completed server notification contract in the existing Android compatibility PR. Server behavior is defined in `../bot/apps/server/src/contract/push.ts` and `../bot/docs/designs/unseen-reply-push.md`.

## Contract

- Replace installation-linked presence with `PUT /v1/chat-attention/views/{viewId}`, sending `{chat: {kind, id}}` or `{chat: null}`.
- Use a random view ID for each native view lifetime and replace it on connection changes. Presence must not depend on notification permission, Firebase, or push registration.
- Report only a mounted bot or group chat in a foreground-active app. Report immediately on attention changes, refresh every 30 seconds, and clear on pause, route leave, logout, or account change.
- Serialize requests for each view. Collapse obsolete pending refreshes so a delayed refresh cannot follow a clear.
- Preserve server `replyReferences` on domain messages from HTTP and event streams. Keep exact event and surface identities.
- Acknowledge exact rendered surfaces through `POST /v1/chat-attention/seen`, with 1-100 distinct event IDs for their original bot or group chat.
- Text acknowledgements require actual nonempty reply content visible within the active viewport. Card acknowledgements require the matching ready card, not a mounted row, summary, loading state, or error. Settled confirmed card content counts.
- Batch acknowledgements and retry failed batches while the same connection remains signed in. Submitted batches retain their original chat across navigation. Never acknowledge incoming events or fetched history without display evidence.
- Refresh the matching active snapshot when a live reply event may precede its history references. Keep unrelated drafts and pending messages intact.

## Architecture

Add a testable coroutine-based attention controller, independent of push delivery and registration. The activity's retained AppModel owns it without retaining the Activity. Activity recreation rotates the view ID but preserves already-earned seen batches in the retained controller. Compose reports the mounted chat, actual surface bounds, and readiness. A stale callback carries its original API identity, view ID, and chat and cannot acknowledge after replacement or navigation.

Before revoking an old session, the model requests a serialized clear through the old API, invalidates old callbacks and seen queues, and rotates identity. Bound this best-effort clear to two seconds; a lost clear expires through the server TTL. An empty-token API is signed out.

Presence and seen retries use bounded backoff. Transient seen failures retry on their original chat. Split a terminally rejected batch into individual acknowledgements so one rejected ID cannot block valid IDs; drop terminally rejected individual IDs. Authentication failure stops old-account retries.

A live-event refresh captures API identity, selection/group version, and snapshot revision. Apply it only if all remain current. A newer streamed snapshot invalidates the pending HTTP response. Use existing optimistic reconciliation.

### Ready surfaces

Wrap actual rendered text/card content, not lazy rows. Intersect its bounds with the chat list viewport, and acknowledge only when active and ready.

- Assistant text: nonempty rendered Markdown text in bot or group chats. Citation chips alone do not count.
- Held action: matching `heldAction.id` with a loaded action, pending or confirmed settled; missing, loading, or unavailable actions are not ready.
- Credential/login: the existing `secretRequestState` is Waiting, Saved, or Declined. Loading and Unavailable are not ready. Match the message's `toolCallId` to the reference.
- Call/bot requests: matching loaded request, pending or confirmed settled; missing request is not ready. Match the message's `toolCallId`.
- Job question cards have no new acknowledgement boundary without a verified producer mapping. The server adds no standalone question event; ordinary referenced reply text remains supported.
- Group cards and collapsed event/notice summaries not currently rendered as those exact surfaces remain unacknowledged.

Keep push registration, notification settings, local delivery, FCM payloads, duplicate tracking, and notification tap routing unchanged. Remove the old presence request and its registration-dependent runtime heartbeat.

## Verification

Add regression coverage for presence without push registration, bot/group identity, foreground and mounted-view gates, serialized clears, account changes, exact surface matching and readiness, batched retry, original-chat retention, and event/reference refresh races. Demonstrate the old behavior fails before fixing it. Run Python contract tests, generated-output checks, Android unit tests, lint, and debug assembly. No emulator-based CI or live providers. Report native viewport/lifecycle behavior not manually verified.
