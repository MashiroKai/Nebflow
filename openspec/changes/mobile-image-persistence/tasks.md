# Tasks

- [x] Publish an owner-only stable copy before sending an accepted browser image upload.
- [x] Persist the copy's local path in the sender's message history and never send that path to the peer.
- [x] Keep existing ledgers without the new field readable; address copies by SHA-256 and retry atomically from the verified bytes.
- [x] Log a capacity warning at 10 GiB and above without deleting copies; no automatic TTL.
- [ ] Release copies when no remaining message references them — the cleanup integration is open because device message deletion is not implemented.
- [ ] Cross-client end-to-end acceptance (actual browser upload, cleared local state, second authenticated client, byte hash, missing-file handling) — NOT executed in this batch.
- [ ] Chat upload durability, Canvas Ask attachments and failed-image retry — separate open work, NOT covered here.
