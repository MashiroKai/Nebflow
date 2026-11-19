## ADDED Requirements

### Requirement: [FCI-REQ-001] Click term previews
Term previews SHALL open only on click and invalidate stale asynchronous requests.

#### Scenario: [FCI-SCN-001] Superseded requests
- **WHEN** a term is closed or another request supersedes it
- **THEN** its late response SHALL NOT open or replace the preview, including when the same term is reopened.

### Requirement: [FCI-REQ-002] Note origin
The Note morph SHALL originate at its own button without changing Note persistence.

#### Scenario: [FCI-SCN-002] Closed Note seed
- **WHEN** Note is closed on desktop or mobile
- **THEN** its seed SHALL align with the Note button.

### Requirement: [FCI-REQ-003] Batch closure
Closing a diff tab SHALL close only the left panel for the same batch and permit reopening.

#### Scenario: [FCI-SCN-003] Matching batch closes
- **WHEN** a diff tab closes
- **THEN** another batch and unrelated panels remain open; the matching batch can be reopened.

### Requirement: [FCI-REQ-004] File selection and labels
Chinese visible Canvas labels SHALL use 看板, and touch users SHALL have an explicit file selection entry.

#### Scenario: [FCI-SCN-004] Select files
- **WHEN** selection mode is enabled in file search
- **THEN** taps select files for deduplicated batch opening without bypassing media routing.
- **AND** button and keyboard batch opening preserve selected media and dispatch it through the platform media route; accessible option selection reflects multi-selection independently of keyboard focus.
