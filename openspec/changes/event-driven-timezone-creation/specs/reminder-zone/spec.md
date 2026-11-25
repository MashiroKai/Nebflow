## ADDED Requirements

### Requirement: [ETZ-REQ-001] Reminder creation region
The system SHALL persist the creation region and due rule when a valid reminder deadline is created. It SHALL request clarification when the applicable write port has no valid region, rather than guessing the server region.

#### Scenario: [ETZ-SCN-001] Create a reminder from a device
- **WHEN** a WebSocket todo creation supplies a nonzero deadline and a valid device region
- **THEN** the system persists the deadline and creation region.

### Requirement: [ETZ-REQ-002] Explicit region correction
The system SHALL preserve occurrence identity and triggered history while recalculating unconsumed occurrences and the future cursor under an explicitly corrected region.

#### Scenario: [ETZ-SCN-002] Correct an unconsumed occurrence
- **WHEN** a creation region is explicitly corrected
- **THEN** an unconsumed occurrence is recalculated under the new rule and an elapsed result is marked missed rather than delivered as catch-up.

### Requirement: [ETZ-REQ-003] Rollback safety
The deployment procedure SHALL retain a complete data snapshot before rollback and prohibit old-version writes to task data during rollback, without migration or dual-write.

#### Scenario: [ETZ-SCN-003] Roll back a reader
- **WHEN** an older version reads task data containing new fields
- **THEN** the fields are ignored on read and would be lost on old-version writeback.
