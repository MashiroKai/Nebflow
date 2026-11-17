## ADDED Requirements

### Requirement: [BG-REQ-001] Branch governance guards

New development branches SHALL use feat/* or bug/* and derive from feat/base, except release hotfix bug/* branches may derive from main with target main. MR checks SHALL accept feat/*, bug/* and grandfathered fix/* to feat/base, main to feat/base, feat/base to main, and bug/* to main only with an auditable main baseline SHA that is an ancestor of both main and source HEAD. Hotfixes require author approval; repository checks cannot prove authenticity or grant authority. Missing MR input SHALL fail closed. Push checks SHALL reject main and release/* updates and deletions, and feat/base creation, deletion and non-fast-forward updates, unless explicitly overridden; malformed input SHALL fail closed. Only the author may create or delete feat/base. Server-side permissions, not local checks, enforce release authority.

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
