## ADDED Requirements

### Requirement: [MIP-REQ-001] Browser device images are published to an owner-only stable copy

When a browser image upload is accepted the engine SHALL publish an owner-only stable copy before
sending it, SHALL persist that copy's local path in the sender's message history, and SHALL NOT send
that local path to the peer; the sender's authenticated clients SHALL resolve the history path
through the existing file-ticket route. A failed publication SHALL NOT be represented as a
successful durable image. Image copies SHALL have no automatic TTL: a capacity warning is logged at
10 GiB and above without deleting copies.

#### Scenario: [MIP-SCN-001] The sender keeps a readable copy

- **WHEN** a browser image upload is accepted and the browser later loses its file handle
- **THEN** the sender ledger still resolves the published copy as an absolute readable path while the peer received no local path

#### Scenario: [MIP-SCN-002] Capacity warning threshold

- **WHEN** published image copies reach the warning threshold
- **THEN** a warning is recorded at 10 GiB and above without deleting any copy
