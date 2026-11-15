## ADDED Requirements

### Requirement: [DSA-REQ-001] Delegate dispatcher reconnaissance

A delegate with persisted mode `dispatcher` SHALL expose and be permitted to call the existing blocking `Subagent` tool for reconnaissance. Dispatcher task execution SHALL continue to use chain nodes.

#### Scenario: [DSA-SCN-001] Dispatcher tool face and validation

- **WHEN** a delegate session has persisted mode `dispatcher`
- **THEN** its allowed tools SHALL include `Subagent`, and a call with an empty prompt SHALL return the normal missing-prompt validation result rather than a dispatcher-mode prohibition.

#### Scenario: [DSA-SCN-002] Dispatcher receives child result and cleanup

- **WHEN** the dispatcher calls `Subagent` with a valid self-contained prompt and the child finishes
- **THEN** the tool SHALL return the child's final text, record a terminal task result, and release the child session and subtask registry entry using the existing lifecycle.

#### Scenario: [DSA-SCN-003] Role boundaries remain intact

- **WHEN** dispatcher reconnaissance is enabled
- **THEN** kernel rejection of other child definitions SHALL remain unchanged, and dispatcher instructions SHALL distinguish reconnaissance from task-chain execution.

### Requirement: [DSA-REQ-002] Subagent Bash capability and isolation

The builtin Subagent SHALL expose exactly Read, Glob, Grep, WebSearch, WebFetch and Bash. Bash SHALL use the existing execution and permission pipeline. The role SHALL NOT be described as mechanically read-only. No Write, Edit, messaging or recursive agent tools SHALL be added. Ask sessions SHALL retain an independently enumerated tool face rather than inherit Subagent capabilities.

#### Scenario: [DSA-SCN-004] Shell reconnaissance result

- **WHEN** a Subagent invokes a safe foreground Bash command and then finishes its findings
- **THEN** the command SHALL execute through the existing tool pipeline, its output SHALL be available to the child, and the parent SHALL receive final findings followed by normal child cleanup.

#### Scenario: [DSA-SCN-005] Fixed faces remain independent

- **WHEN** Subagent receives Bash
- **THEN** the actual child tool face SHALL contain exactly the six named tools, Ask SHALL use its own explicit allowlist, and custom child declarations SHALL NOT widen the fixed Subagent face.

#### Scenario: [DSA-SCN-006] Truthful shell guidance

- **WHEN** role and tool guidance describe Subagent
- **THEN** they SHALL allow shell-backed reconnaissance without claiming shell access is mechanically read-only, SHALL direct the child to complete foreground shell work before final findings, and SHALL preserve dispatcher chain-task discipline.

### Requirement: [DSA-REQ-003] Ask shell investigation replaces GitQuery

Ask SHALL expose exactly message_record_search, send_message_to_main_chat, Read, Glob, Grep, WebSearch, WebFetch and Bash. Bash SHALL use the existing execution and permission pipeline and SHALL NOT be treated as Ask-exclusive. The dedicated GitQuery tool SHALL be retired from registration and implementation. Ask SHALL NOT claim a mechanically read-only boundary after gaining Bash.

#### Scenario: [DSA-SCN-007] Ask Bash face and GitQuery retirement

- **WHEN** Ask tools are assembled, including a definition requesting broader capabilities
- **THEN** the actual Ask face SHALL equal the explicit eight-tool allowlist, SHALL include Bash and exclude GitQuery, and non-Ask roles SHALL retain their existing Bash permission.

#### Scenario: [DSA-SCN-008] Ask shell working directory and result

- **WHEN** an Ask session with a valid bound file first executes a Bash command
- **THEN** its initial shell directory SHALL be the bound file's parent directory, the command result SHALL return through the existing tool pipeline, and session cleanup SHALL release the shell.

#### Scenario: [DSA-SCN-009] Ask guidance reflects shell capability

- **WHEN** Ask context and tools are described
- **THEN** they SHALL advertise Bash instead of GitQuery, SHALL NOT state that no execution tool exists, and SHALL retain the explicit user-request requirement for forwarding messages to the main session.
