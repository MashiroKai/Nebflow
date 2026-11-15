## ADDED Requirements

### Requirement: [FDC-REQ-001] Isolate file saves
A note's pending writes and acknowledgements MUST belong to that file and write. Read errors MUST NOT be treated as an empty note.

#### Scenario: [FDC-SCN-001] Switch before pending saves
- **WHEN** two files are edited before their save timers fire
- **THEN** each write retains its own file and text

#### Scenario: [FDC-SCN-002] Late receipt
- **WHEN** a receipt for another file arrives
- **THEN** it does not change the current file status

#### Scenario: [FDC-SCN-003] Read failure
- **WHEN** reading a note fails
- **THEN** local text is retained and no write is sent over the unreadable note

### Requirement: [FDC-REQ-002] Conditional note writes
The store MUST atomically compare versions and reject stale and unversioned writes; a clear MUST retain a versioned tombstone and old records MUST remain readable.

#### Scenario: [FDC-SCN-004] Stale and concurrent writes
- **WHEN** writers use an outdated or identical base
- **THEN** conflicting content is refused without overwriting the stored record

#### Scenario: [FDC-SCN-005] Old caller and clear
- **WHEN** an unversioned caller writes or a note is cleared
- **THEN** the old caller is refused and a clear is recorded with a new version

### Requirement: [FDC-REQ-003] Protect todo ownership
Human todo creation MUST append an independent ID; named tool rearming MUST NOT replace human or unknown-source todos.

#### Scenario: [FDC-SCN-006] Same-name creation
- **WHEN** the interface creates two todos of the same name
- **THEN** both persist with different IDs

#### Scenario: [FDC-SCN-007] Tool rearm
- **WHEN** the tool rearms a name shared by human and unknown-source records
- **THEN** the rearm is refused without changes if unknown-origin records exist; otherwise only explicitly tool-owned records in the same session are replaced

### Requirement: [FDC-REQ-004] Preserve drafts and unambiguous references
A dirty Note draft MUST retain its base and text across reload, including an empty clear. A conflict MUST retain both versions and offer an explicit choice. Duplicate-name todo references MUST NOT silently choose the first result.

#### Scenario: [FDC-SCN-008] Initial read races an edit
- **WHEN** an edit precedes a different server response
- **THEN** both versions are retained for explicit resolution

#### Scenario: [FDC-SCN-009] Offline empty draft
- **WHEN** a dirty note is cleared offline and the page reloads
- **THEN** the empty draft and its base version are retained

#### Scenario: [FDC-SCN-010] Duplicate names
- **WHEN** two open todos have the same name
- **THEN** a plain name is unresolved and a name carrying an ID resolves that exact record

#### Scenario: [FDC-SCN-011] Cross-session rearm
- **WHEN** an owned record lives in a different session
- **THEN** it moves to the requested session with the same ID, and a failure before the source copy is removed leaves one recoverable duplicate rather than losing the record

### Requirement: [FDC-REQ-005] Stable rearm identity and proven provenance
A named rearm MUST keep the stored record ID and creation time; legacy ownership MUST be adopted only by an explicit marker, and an unprovable record MUST be refused before any write.

#### Scenario: [FDC-SCN-012] Unreadable stored note
- **WHEN** the stored note record cannot be read
- **THEN** reads and writes report a read error and the record is not overwritten

#### Scenario: [FDC-SCN-013] Client read error
- **WHEN** a Note read fails on the client
- **THEN** the local draft is kept and no write is sent

#### Scenario: [FDC-SCN-014] Repeated rearm
- **WHEN** the same name is re-armed more than once
- **THEN** every call reports the same stored ID and exactly one record remains

#### Scenario: [FDC-SCN-015] Failing disk write
- **WHEN** the atomic replace fails at the filesystem
- **THEN** the stored record is byte-for-byte unchanged and a retry keeps its ID

#### Scenario: [FDC-SCN-016] Partial cross-session move
- **WHEN** the destination write succeeded but the source copy was not removed
- **THEN** the next rearm converges to one record with the same ID

#### Scenario: [FDC-SCN-017] Legacy tool task
- **WHEN** a legacy named scheduled task has no source
- **THEN** it is adopted as tool-owned with its existing ID, because only the tool names that kind

#### Scenario: [FDC-SCN-018] Unknown legacy todo
- **WHEN** a legacy named todo has no source and a tool re-arms that name
- **THEN** the rearm is refused, the record is kept, and no file is written

#### Scenario: [FDC-SCN-019] Legacy engine mirror
- **WHEN** a legacy mirror carries the reserved engine prefix
- **THEN** attach adopts it and retire removes it while another-source row with the same name is kept

#### Scenario: [FDC-SCN-020] Late Note receipt
- **WHEN** a receipt or read for an older write arrives after a newer state exists
- **THEN** it does not lower the known base or clear the current in-flight write, and a receipt whose stored text equals the draft adopts that version without reopening a choice

#### Scenario: [FDC-SCN-021] Explicit conflict choice
- **WHEN** a conflict retains both versions
- **THEN** choosing the server copy adopts its text and version without another write, and an unrecognised choice changes nothing

#### Scenario: [FDC-SCN-022] Unknown Note base
- **WHEN** the client has no known base version
- **THEN** it does not send a put until a server read establishes one, and a version-required receipt carrying the same text adopts that base

#### Scenario: [FDC-SCN-023] Cleared draft
- **WHEN** an explicit clear is acknowledged
- **THEN** the empty draft and its new base survive a reload

#### Scenario: [FDC-SCN-024] Real protocol entry
- **WHEN** the executable live entry runs against an isolated instance
- **THEN** a real socket proves the version, clear, conflict, duplicate and reconnect refusals

#### Scenario: [FDC-SCN-025] Duplicate copy after a partial move
- **WHEN** a partial cross-session move left the same ID in two session files
- **THEN** reads select the highest revision before filtering, mutations persist one authoritative update before removing stale copies, and cancellation removes every copy; conflicting payloads at the same revision are reported and excluded from automatic firing rather than guessed

#### Scenario: [FDC-SCN-026] Retry receipt after completion
- **WHEN** a correlated todo create is retried after the durable row was completed
- **THEN** the receipt reports the stored completed state, not the request's initial snapshot

#### Scenario: [FDC-SCN-027] Note wiring
- **WHEN** the note input handler, a reconnect or a flush runs
- **THEN** the draft is marked dirty and persisted, the reconnect clears the old flight, and the push uses the known base

#### Scenario: [FDC-SCN-028] Parked create retry
- **WHEN** a mobile create retry is pinned to another session or an edited row
- **THEN** the user can discard that retry identity, keep the text, and create again without deleting any stored row

#### Scenario: [FDC-SCN-029] Note size and session guards
- **WHEN** the text exceeds 256 KiB of UTF-8 or the session id is malformed
- **THEN** the write is refused with its own error and nothing is stored

#### Scenario: [FDC-SCN-030] Legacy markdown migration
- **WHEN** a note predates versioning
- **THEN** it reads with its modification time as the version and migrates on the first accepted write
