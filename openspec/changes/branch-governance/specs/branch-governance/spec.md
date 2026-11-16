## ADDED Requirements

### Requirement: [BG-REQ-001] Branch governance guards

The guard SHALL validate Git branch syntax and accept only main, feat/*, bug/*, achieve/*, release/beta and release/stable; MR sources SHALL be restricted to valid feat/* and bug/* names. Push checks SHALL reject updates and deletions to main and release/* unless explicitly overridden; malformed hook input SHALL fail closed.

#### Scenario: [BG-SCN-001] branch and MR checks

- **WHEN** branch and MR source names are checked
- **THEN** valid allowed names pass and invalid or unauthorized names fail.

#### Scenario: [BG-SCN-002] push destination checks

- **WHEN** Git pre-push ref records are checked
- **THEN** protected destinations fail without an explicit override, including deletion and multi-ref pushes, and malformed records fail even with an override.

### Requirement: [BG-REQ-002] Downstream release flow

The author SHALL promote verified main changes to release/beta and, after beta verification, to release/stable. Release automation SHALL select the corresponding channel on those branches and SHALL NOT write changes back to main.

#### Scenario: [BG-SCN-003] Release branch routing

- **WHEN** a pipeline runs on release/beta or release/stable
- **THEN** the full GitLab pipeline and the corresponding release channel are selected, while legacy branch names do not select them.

#### Scenario: [BG-SCN-004] No upstream writeback

- **WHEN** stable release automation runs
- **THEN** it does not commit or push version changes to main.
