## ADDED Requirements

### Requirement: [SR-REQ-001] Bounded orbit reconciliation
Orbit reconciliation MUST terminate for folded or rebuilt cards without three orbit rings and MUST preserve completion behavior for valid three-ring nodes.

#### Scenario: [SR-SCN-001] Folded chain reaches a terminal state
- **WHEN** a folded chain changes from running to completed, failed or cancelled
- **THEN** its observer stops producing self-triggered mutations and scheduled callbacks can run

#### Scenario: [SR-SCN-002] Rebuilt node loses its rings
- **WHEN** a tracked orbit node loses its ring elements
- **THEN** stale phase state is released once without repeated mutation

### Requirement: [SR-REQ-002] Unchanged task snapshots do not invalidate views
The scoped task provider MUST retain historical terminal records while avoiding update notifications and object replacement for unchanged snapshots.

#### Scenario: [SR-SCN-003] Repeated historical snapshot
- **WHEN** a poll returns the same 1,070 historical task rows
- **THEN** no task or mirror update is emitted and unchanged row identities remain stable

#### Scenario: [SR-SCN-004] Initial historical snapshot reconciles terminal children
- **WHEN** the first snapshot includes 1,070 terminal child tasks
- **THEN** each child is reconciled and each affected root indicator is refreshed only once
- **AND** a closed background-task dropdown does not build its list until opened
- **AND** opening the dropdown renders the current records without discarding history

### Requirement: [SR-REQ-003] Attachment observer reaches a fixed point
Attachment folding MUST stop writing observed attributes once the desired strip state is reached.

#### Scenario: [SR-SCN-005] Empty single-line attachment strip
- **WHEN** a single-line strip is empty or its last attachment is removed while expanded
- **THEN** expansion and the more button are cleared without repeated observer notifications
- **AND** the wrapping desktop strip remains unaffected
