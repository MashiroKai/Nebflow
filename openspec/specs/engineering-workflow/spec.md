## Purpose

Define versioned intent and acceptance artifacts for future Stand engineering changes. This capability covers the OpenSpec artifact workflow and its explicit tooling scope, not historical reconstruction or a second engineering rulebook.

## Requirements

### Requirement: [EW-REQ-001] Proportional versioned intent

Contributors SHALL record future behavioral or engineering-contract changes as a proposal, design decisions, tasks, and capability deltas before implementation. Active proposals SHALL NOT require promotion into accepted current specifications. Artifact detail SHALL be proportional to risk; documentation-only corrections without contract changes MAY update existing prose directly.

#### Scenario: [EW-SCN-001] Inspect a bounded engineering change

- **WHEN** a reviewer opens a behavioral change under `openspec/changes/<change>/`
- **THEN** its proposal, design, tasks, and deltas explain intent, acceptance, and exclusions without claiming unimplemented repository-wide coverage
- **AND** proposed additions may coexist with untouched accepted current specifications

### Requirement: [EW-REQ-002] Stable acceptance identities

Specifications and deltas SHALL use globally unique stable IDs with a capability prefix, `REQ` or `SCN`, and a numeric suffix. Each non-removed requirement SHALL have acceptance scenarios. Each delta scenario SHALL map to its requirement, implementation paths, and named tests or a reasoned manual exception. REMOVED requirements MAY instead map the requirement ID with Reason and Migration prose and no scenario.

#### Scenario: [EW-SCN-002] Validate acceptance links

- **WHEN** a contributor validates a complete proposed change
- **THEN** each delta scenario has exactly one mapping and resolvable implementation and test links or a complete manual exception
- **AND** a new capability can use a new stable prefix without changing checker code

#### Scenario: [EW-SCN-003] Reject invalid acceptance artifacts

- **WHEN** artifacts contain malformed or duplicate IDs, missing mappings, unsafe paths, missing linked files, or empty tests without a reasoned manual exception
- **THEN** the checker fails instead of claiming traceability

### Requirement: [EW-REQ-003] Explicit bounded diff coverage

Without a base the checker SHALL validate artifacts and explicitly skip diff coverage. With an explicit resolvable commit base it SHALL enforce the union of current and base tooling-scope registries and associate changed current requirements with matching deltas by identity and content. File-level mappings alone SHALL NOT authorize unrelated requirement or scenario changes. Historical application paths outside that registry SHALL remain outside enforcement.

#### Scenario: [EW-SCN-004] Run without a base

- **WHEN** the checker runs without `--base`
- **THEN** it validates artifacts and links but explicitly reports that changed-path coverage was not checked

#### Scenario: [EW-SCN-005] Enforce an explicit diff

- **WHEN** a valid base is supplied
- **THEN** committed, staged, unstaged, and nonignored untracked scoped changes require active mappings, except validated archive-transition artifact moves and associated current reconciliation
- **AND** renames account for old and new paths and deletions use explicit deleted references
- **AND** new deletion claims prove removal in the supplied diff; an active claim already recorded under the same requirement/scenario in the same base trace may persist when the path is absent both now and at the base, without exempting unrelated changes from coverage
- **AND** current additions, edits, and removals match associated ADDED, MODIFIED, and REMOVED deltas by ID and content, not merely a prior file mapping
- **AND** `openspec/tooling-scope.json` explicitly covers this batch's tooling, configuration, CI, contributor guidance, and named stale-file deletions without covering the whole application tree

#### Scenario: [EW-SCN-006] Refuse an unknown base

- **WHEN** a base value is missing, cannot resolve to a commit, or cannot be evaluated at the supplied repository root
- **THEN** validation fails rather than falling back to artifact-only success

### Requirement: [EW-REQ-004] Historical artifact integrity

Archives SHALL retain valid IDs and mappings without requiring historical linked files. Explicit-base acceptance SHALL require completed tasks and exact current reconciliation, including absent removals. Base-active archives SHALL preserve approved artifacts. New same-diff archives SHALL validate live links and cover only their artifacts, matching changed implementation paths, and current reconciliation. Existing archives SHALL NOT authorize later code edits.

#### Scenario: [EW-SCN-007] Inspect and accept an archive

- **WHEN** historical implementation or test files disappear
- **THEN** an archive still validates structurally while unsafe paths fail
- **WHEN** the last active change moves to a dated archive with completed tasks and accepted current reconciliation
- **THEN** explicit-base validation succeeds without inventing another bookkeeping change
- **AND** a fully new change may be proposed, implemented, and archived in one diff when the base has no same-name active or archived change, live trace links validate, tasks are complete, and current reconciliation matches
- **AND** incomplete tasks, changed base-approved artifacts, missing live links, removal mismatches, and unrelated scoped changes still fail
- **AND** archives already present in the base cannot use old implementation mappings to authorize new code changes

### Requirement: [EW-REQ-005] Single reusable engineering rule source

The workflow SHALL link to the independently versioned Stand dev source instead of reproducing its full engineering rulebook. Guidance SHALL load only task-relevant references. Updating artifacts SHALL NOT imply installation or authorize rewriting human-owned instructions.

#### Scenario: [EW-SCN-008] Find engineering guidance

- **WHEN** a contributor reads `openspec/README.md`
- **THEN** it directs them to independent source guidance and relevant references while limiting local instructions to artifact and query mechanics
