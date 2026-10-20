# Changelog

## Unreleased

- Preserve admitted task execution fields for navigation and reject non-admitted or incomplete starts instead of reporting success.

- Pin the bot's `1.0.0` contract as a checksum-verified local export with committed source provenance.
- Generate Kotlin HTTP operations and serializable wire types with repository-owned generator `1.0.0` and a pinned transport template.
- Route every `APIClient` endpoint through generated operations while retaining `HttpTransport`, `HttpURLConnection`, coroutines, bearer authentication, and existing timeouts.
- Generate SSE payload types from the exported JSON Schemas and decode them before adapting to app state.
- Preserve PATCH omission versus explicit null, binary attachments, bounded API errors, and cancellation cleanup.
- Reject responses missing required contract fields instead of silently supplying handwritten wire defaults. Update transport fixtures to represent valid responses.
- Verify committed generated output byte-for-byte in public CI. Add explicit local/release update commands and a scheduled, self-checking update PR workflow.

The bootstrap pin must switch to the immutable `contract-v1.0.0` release after bot publishes it, before merging the consumer update.
