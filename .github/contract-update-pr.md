## Summary

Update the immutable bot contract pin and committed Kotlin client. Review `contract/assets/CHANGELOG.md`, the provenance, and generated changes together.

## Why

Keep Android operations and wire types aligned with the bot's latest published contract release.

## Evidence

Before reaching the PR step, this updater runs:

- Checksums for every contract payload asset.
- Offline generator and updater tests.
- Byte-for-byte regeneration checks.
- `./gradlew test` and Android lint.

The workflow uses only `GITHUB_TOKEN`. Follow-up PR workflows can require approval or remain suppressed; this updater does not rely on them.

## Risk & Rollback

**Reversibility:** Reversible by reverting the contract pin and generated output together.

**Blast Radius:** Android HTTP requests, response decoding, and SSE payload decoding.

Schema updates can expose API or app-adapter incompatibilities. Review the generated changes and test results before merging.
