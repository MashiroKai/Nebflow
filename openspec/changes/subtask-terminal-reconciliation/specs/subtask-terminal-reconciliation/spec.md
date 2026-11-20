## ADDED Requirements

### Requirement: [STR-REQ-001] Terminalization retries before declaring a lost terminal

A managed subagent terminal row write SHALL retry a bounded number of times on transient failure.
When every attempt fails, the run leaves no authoritative evidence and the row is treated as
unresolved rather than reported as terminal.

#### Scenario: [STR-SCN-001] Injected I/O failure during the terminal commit

- **WHEN** the terminal row commit raises an I/O error on its first attempt
- **THEN** the commit is retried and the row reaches its terminal status with one parent notification

### Requirement: [STR-REQ-002] Authoritative completion evidence is run-bound and source-checked

Terminal evidence for a managed run SHALL be a persisted terminal envelope or a run-bound completion
debt, and both SHALL pass one shared identity check: the row still points at that run, the event
source equals the row source, the correlation id is the task id, the parent session id matches, and
`metadata.taskRunId` equals the run. A missing run identity SHALL fail the check.

#### Scenario: [STR-SCN-002] Envelope without a run identity

- **WHEN** a completed envelope exists for an unresolved row but carries no `taskRunId`
- **THEN** reconciliation does not terminalize the row

#### Scenario: [STR-SCN-003] Envelope and debt disagree

- **WHEN** both evidences are present but their completion messages differ, or one fails identity
- **THEN** the row is flagged for manual review and is not terminalized or delivered

#### Scenario: [STR-SCN-004] Envelope and debt agree

- **WHEN** both evidences are present, pass identity, and carry identical completion messages
- **THEN** the row is terminalized once and delivered once

### Requirement: [STR-REQ-003] Completion debt is fenced against cancel and run replacement

A managed completion debt SHALL be written only while the row still points at that run and is not
terminal. The row lock is taken before the debt lock, never the reverse, and the row-lock section
SHALL contain only validation and a single deferral-free file write.

#### Scenario: [STR-SCN-005] First committed evidence wins

- **WHEN** a run has already reached a terminal status through its envelope
- **THEN** a later debt write for the same run is refused and the terminal result is unchanged

#### Scenario: [STR-SCN-006] Cancelled run refuses stale debt

- **WHEN** a run was cancelled and a completion debt for that run arrives afterwards
- **THEN** the debt is refused and the row stays cancelled

#### Scenario: [STR-SCN-007] Debt write races a cancel and a new run

- **WHEN** a debt write runs concurrently with cancel and a new `beginRun` for the same task
- **THEN** the old run's debt never binds the new run, and recovery refuses it as evidence

### Requirement: [STR-REQ-004] Unresolved rows are flagged, never invented or killed

Recovery SHALL NOT terminalize a `subagent`/`subtask` row that has no authoritative evidence and
SHALL NOT kill it on a timeout. Such a row SHALL carry an externally visible suspected-lost-terminal
marker with one bounded alert, and only an explicit manual force-terminalize call may dispose of it.

#### Scenario: [STR-SCN-008] No evidence at startup

- **WHEN** restart recovery finds a running row with neither envelope nor debt
- **THEN** the row keeps its status and is flagged as suspected lost terminal

#### Scenario: [STR-SCN-009] Manual disposal is restricted to flagged rows

- **WHEN** force-terminalize is called for an unflagged row
- **THEN** the call is refused with `not-flagged`

#### Scenario: [STR-SCN-010] Transcript text is not evidence

- **WHEN** only the child's last assistant text exists and no terminal evidence is present
- **THEN** recovery does not infer completion

### Requirement: [STR-REQ-005] Interrupted projections require proof of non-delivery

An `interrupted` projection SHALL NOT be rewritten from completion evidence unless non-delivery of
the interruption notice is provable. An empty or unreadable mailbox receipt SHALL be treated as
unknown, never as proof of non-delivery.

#### Scenario: [STR-SCN-011] Empty receipt is not proof

- **WHEN** completion evidence exists for an interrupted row and the mailbox receipt lookup returns
      an empty set
- **THEN** the row stays interrupted and is flagged for manual review

#### Scenario: [STR-SCN-012] Corrupt receipt is not proof

- **WHEN** the delivered-receipt file is corrupt and degrades to an empty lookup
- **THEN** the row stays interrupted and is flagged for manual review

### Requirement: [STR-REQ-006] Blocking subagent delivery evidence is qualified per tool result

The blocking `Subagent` primitive's parent-visible tool return is its delivery. Trust SHALL be
recorded per tool-use id, and reconciliation SHALL match a legacy row's tool-call id against a
result qualified for that same id in the same parent session.

#### Scenario: [STR-SCN-013] Synthetic result inside the same batch

- **WHEN** a trusted subagent result and a synthetic result share one transcript message
- **THEN** only the row whose tool-call id is qualified is reconciled

#### Scenario: [STR-SCN-014] Legacy row without a tool-call id

- **WHEN** a legacy blocked row carries no tool-call id
- **THEN** it is never reconciled by ordering or timing and stays flagged for review

### Requirement: [STR-REQ-007] Supervisor replay validates before delivering

Every consumer of a persisted terminal envelope SHALL validate identity before acting. A committed
but unbound protected envelope SHALL NOT be delivered to the parent and SHALL be flagged instead.

#### Scenario: [STR-SCN-015] Wrong-source protected envelope reaches the supervisor

- **WHEN** a protected session completes and its committed envelope carries a different source
- **THEN** the supervisor delivers nothing to the parent mailbox

#### Scenario: [STR-SCN-016] Notification replay with an invalid envelope

- **WHEN** startup replays a pending terminal notification whose saved envelope fails identity
- **THEN** nothing is enqueued and the notification stays pending

### Requirement: [STR-REQ-008] Evidence-only reconciliation without shared resources

Startup without `SharedResources` SHALL still terminalize an unresolved run from authoritative
evidence, without marking or mailbox work.

#### Scenario: [STR-SCN-017] Resource-free startup with a saved envelope

- **WHEN** startup runs without shared resources and a valid envelope exists for a running row
- **THEN** the row is terminalized from that envelope
