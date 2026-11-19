## ADDED Requirements

### Requirement: [PTG-REQ-001] The instruction-file gate is keyed on target and risk class

An opaque command SHALL be judged by the operation it performs — write, delete, network egress or
credential access — and SHALL NOT be judged by which file name appears in its text. The human-owned
instruction file SHALL remain a protected target: the direct Write/Edit/MultiEdit file-path gate is
unchanged, and an opaque command is blocked only when the target and the risk class hold together.
Read-only commands SHALL carry no risk class and therefore require no approval.

#### Scenario: [PTG-SCN-001] Read-only commands and look-alikes

- **WHEN** read-only git history, diff or pipeline commands are run, or a risk-substring look-alike such as `curling` appears
- **THEN** no risk class is produced and no approval is required

#### Scenario: [PTG-SCN-002] Target and risk together

- **WHEN** a write, delete, egress or credential primitive names the instruction file
- **THEN** the command requires confirmation, while the same primitive on another file does not gate

### Requirement: [PTG-REQ-002] The permission wait is bounded and fail-closed

A permission wait SHALL be bounded by a deadline and SHALL be fail-closed on expiry: denial, a
readable tool result and an exact-card close. A receipt before the deadline SHALL resume the waiter.
The deadline SHALL be the one already landed by the permission-landing batch, and this change SHALL
NOT add a second wait seam.

#### Scenario: [PTG-SCN-003] Deadline and early receipt

- **WHEN** the permission wait reaches its deadline, or a receipt arrives before it
- **THEN** the expiry denies with a distinct error and closes only its own card, while an early receipt resumes the waiter
