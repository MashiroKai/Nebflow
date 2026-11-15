## Purpose

Define checkout-only quality checks and the limits of their evidence. Static execution, link completeness, and upstream OpenSpec validation are separate from application, browser, backend, and human acceptance.

## Requirements

### Requirement: [QG-REQ-001] Shared bounded static execution

Local and CI static entrypoints SHALL delegate shared check membership to an explicit command registry. Registry commands SHALL use argument arrays without a shell and bounded subprocess execution. A nonzero exit, spawn failure, signal, or timeout SHALL remain a failed check. The runner SHALL identify the check and outcome. Runtime, browser, JVM compilation, and installed-plugin checks SHALL remain separately named verification rather than being implied by static success.

#### Scenario: [QG-SCN-001] Select reproducible static checks

- **WHEN** local or CI tooling selects the static profile
- **THEN** it obtains the same declared check membership, including SDD artifact validation, scoped tooling lint, and script tests
- **AND** explicit-base SDD checking requires its separately named profile and supplied base

#### Scenario: [QG-SCN-002] Preserve subprocess failures

- **WHEN** a registry check fails to spawn, exits nonzero, is signaled, or exceeds its timeout
- **THEN** the runner reports a failure and does not convert it into a successful check

### Requirement: [QG-REQ-002] Fail-closed JavaScript type diagnostics

The JavaScript type gate SHALL permit baseline comparison only for clean compiler success or recognized source diagnostics. Configuration failures, unexpected output, spawn failures, signals, and timeouts SHALL fail the check rather than produce a zero-error result. Existing new-file, diagnostic-count, and strict-file rules SHALL remain effective without raising the baseline to conceal failures.

#### Scenario: [QG-SCN-003] Recognize diagnostics without hiding compiler failure

- **WHEN** the compiler returns clean success or a recognized source-diagnostic exit
- **THEN** the type gate can compare recognized counts to the existing baseline
- **AND** compiler crashes, configuration errors, unexpected output, missing compiler, signals, or timeouts fail closed

### Requirement: [QG-REQ-003] Evidence boundaries

The SDD checker SHALL report link completeness separately from test execution. It SHALL NOT execute commands supplied by trace records. Named test references SHALL be checked as file and literal-name links, not interpreted as proof that assertions ran or passed. A manual exception SHALL state a substantive reason, reproducible steps, and the expected observation; its presence SHALL NOT claim that a reviewer performed those steps.

#### Scenario: [QG-SCN-004] Report structural completeness honestly

- **WHEN** all SDD links validate
- **THEN** the checker reports that test execution and manual acceptance were not performed
- **AND** trace-provided command fields are rejected rather than executed
- **AND** the separate official OpenSpec runner uses the version and SHA-pinned toolchain declaration, rejects unsafe versions and validator failures, and reports structure validation without claiming implementation correctness

### Requirement: [QG-REQ-004] Focused regression coverage

The new SDD checker SHALL have isolated `node:test` coverage for valid artifacts and invalid identity, link, path, base, diff, and archive cases. Fixtures SHALL use temporary roots and clean up after tests. Tooling lint SHALL cover the new engineering scripts without presenting that bounded coverage as whole-repository JavaScript lint.

#### Scenario: [QG-SCN-005] Exercise positive and negative fixtures

- **WHEN** the focused SDD regression suite runs
- **THEN** valid artifacts pass and invalid fixtures fail as expected, including renames, deletions, unknown bases, and archives with historical file removal
- **AND** temporary fixture files are removed after each test
