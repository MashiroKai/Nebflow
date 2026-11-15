## ADDED Requirements

### Requirement: [PG-REQ-001] Preserve meaningful static acceptance

Guard maintenance SHALL preserve existing safety constraints without increasing baselines or hiding production violations. Textual assertions SHALL follow current production contracts, and precision exceptions SHALL NOT hide unrelated violations on the same line.

#### Scenario: [PG-SCN-001] Reject safety regressions

- **WHEN** a guard is updated to match current code
- **THEN** positive fixtures preserve the legitimate behavior
- **AND** negative mutations removing the guarded behavior still fail

#### Scenario: [PG-SCN-002] Classify local identity precisely

- **WHEN** a legitimate numeric or font identifier appears beside a personal path
- **THEN** the legitimate identifier does not itself fail
- **AND** the personal path still fails

### Requirement: [PG-REQ-002] Repair implementation instead of increasing baselines

Source repairs SHALL use existing component ownership, design tokens, type contracts and package boundaries. Genuine architectural violations SHALL remain visible until explicitly resolved.

#### Scenario: [PG-SCN-003] Verify repaired source

- **WHEN** frontend and unused-import repairs are validated
- **THEN** existing gates pass for the repaired behavior without baseline increases
- **AND** remaining architectural or review-dependent blockers are reported rather than waived
