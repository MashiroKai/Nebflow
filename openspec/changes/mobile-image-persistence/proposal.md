# Persist outgoing device images

## Intent

Outgoing browser-selected device images must remain readable after the browser loses its file handle. This changes the previous zero-retention behavior for browser image transfers; agent-origin files and non-image transfers retain their existing behavior. Existing chat upload and AskUser orphan cleanup rules are unchanged.

## Requirements and scenarios

- When a browser image upload is accepted, the engine must publish an owner-only stable copy before sending it, persist its local path in the sender's message history, and never send that local path to the peer.
- The sender's authenticated clients resolve that history path using the existing file-ticket route. A failed publication must not be represented as a successful durable image.
- Existing ledgers without the new field remain readable. Copies are addressed by SHA-256; retry publishes atomically from the newly verified upload bytes.
- Image copies have no automatic TTL. Log a capacity warning at 10 GiB and above without deleting copies. This is a warning threshold, not a write-enforced quota.
- Deleting a device message or conversation must eventually release only copies that no remaining message references. Device message deletion is not currently implemented, so this cleanup integration remains open.
- Evidence must cover an actual browser upload, clearing its local state, a second authenticated client, byte hash and missing-file handling. A unit test does not replace that acceptance.

## Scope and outstanding work

This delta covers the device browser upload leg only. Chat upload durability, Canvas Ask attachment support, failed-image retry and cross-client end-to-end acceptance remain separate open work; this proposal does not claim those are implemented.
