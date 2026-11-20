# Design

## Evidence contract

Authoritative terminal evidence for a managed row is exactly one of:

- a persisted terminal envelope (`SessionStore.loadTerminalEnvelope(taskId, runId)`), or
- a run-bound completion debt (`CompletionDebtStore.pendingManaged`), written before the parent
  notification and keyed by task + run.

Both pass `SubAgentStartupRecovery.validTerminalEnvelope`, the single identity check used by every
envelope consumer (startup recovery, interrupted review, notification replay, protected supervisor
delivery). It requires: the row still points at this `runId`; `event.source` equals the row source
(`subtask` / `subagent`); `correlationId` equals the task id; `metadata.parentSessionId` equals the
row parent; `metadata.taskRunId` equals the run. A missing `taskRunId` fails the check — run binding
is a hard condition, so envelope-era files without it are treated as unverified, not as valid.

Derived, not authoritative: the last assistant text of a transcript (a child's self-description) is
never completion evidence.

## Lock order

Managed debt writes take the task row lock first and the debt lock second:
`SubAgentTaskStore.withLock` → `CompletionDebtStore.debtLock`. No path may take a task row lock
while holding the debt lock. The row-lock critical section is short: validate that the row still
points at this run and is not terminal, then write a single task+run debt file. Terminal commit,
notification and delivery stay outside the lock.

## Reconciling an unresolved row at startup

For `source ∈ {subagent, subtask}` rows in `running` / `pending-recovery`:

1. Both evidences are read and validated independently.
2. Both valid and identical (`completionMessages`) → terminalize once and deliver once (idempotent
   mailbox identity `taskId#runId`).
3. Either present but failing identity, or both present and differing → flag for manual review, no
   terminalization, no delivery.
4. Neither present → flag for manual review only. Recovery never invents an outcome and never kills
   a row on a timeout.

`executionStatus = interrupted` rows are excluded from envelope/debt reconciliation: an interrupted
projection may already have been delivered, and `completeRun` is first-evidence-wins. Those rows are
flagged for review and left untouched.

Rows are excluded from the legacy salvage chain (`recoverOne`) and from the manual
`pending-recovery` transition, so this reconciliation cannot be bypassed by either mode. When startup
runs without `SharedResources`, only the evidence-based terminalization part runs (no marking, no
mailbox work).

## Legacy blocking subagent rows

The blocking `Subagent` primitive creates a legacy row (no `runId`), and its tool return is its only
delivery — no parent mailbox notice is produced. A parent-visible tool result therefore counts as
delivery evidence, but only when the result is qualified per result
(`ToolExecResult.subagentBoundaryReturn`) and only for the same parent session. Message-level
qualification is insufficient: synthetic results (execution faults, policy denials) share the
message batch, so trust is recorded per tool-use id.

Rows created before this change carry no tool-call id and cannot be attributed safely; they are
never guessed from ordering or timing — they stay unresolved and are flagged for review.

## Not-delivered proof ledger (seam only)

An interrupted projection is auto-repaired only when non-delivery is provable. The existing mailbox
receipts cannot prove it: `delivered.json` is a rolling 1024-entry tombstone, and a missing or
corrupt receipt degrades to an empty set (fail-open). Empty therefore means "unknown", not
"undelivered". Until a ledger with these properties exists, every pre-existing interrupted row stays
flagged for manual review:

- append-only records of "terminal intent for task+run" and "delivery accepted";
- a retained watermark so eviction cannot be mistaken for absence;
- read failure is fail-closed: unreadable ≠ undelivered.

Enable condition: only when (a) that ledger exists and is written before every terminal
notification, and (b) reconciliation reads it fail-closed, may the "provably undelivered → restore
completed from evidence" branch be enabled. Wire-in point is the interrupted branch of
`SubAgentStartupRecovery.markUnresolved`, which currently returns `IO.pure(false)` for interrupted
rows.

## Bounded escalation of the trusted-descendant hold

The descendant segment of the completion gate has no wallclock cap by design: a real child must
never be terminalized because time passed. The failure it leaves open is the *orphan row* — in flight
on disk, absent from the agent registry (crash before re-registration, hidden row after a hard
restart), no progress at all. Such a row held the delegate completion forever, and the only visible
trace was a progress mail every turn.

Escalation leg (`BackoffSupervisor.trustedDescendantHold`,
`Defaults.DelegateDescendantHoldEscalationMs`, prop `nebflow.delegate.descendantHoldEscalationMs`,
default 30 min, `<= 0` disables):

1. Partition the delegate's in-flight child rows by presence in the agent registry.
2. If **every** in-flight row is registry-absent and each has been without progress
   (`activityAt` / `statusAt` / `spawnedAt`) for the escalation window, flag them
   (`suspectedLostTerminal = true`) and release the completion.
3. Otherwise the hold stands, unchanged.

The flag is review material only: escalation never terminalizes anything, and disposal still goes
through the single manual entrance (`forceTerminalizeFlagged`). A legitimately parked child keeps a
live registry entry, so the first condition fails and it is neither flagged nor released — the
"park the child" workflow stays intact. Cancellation wins the race: a row cancelled before the gate
reads it is out of the in-flight set and is untouched.

## Startup ownership boundaries

`interruptExternalRuns` declares a run `interrupted` only for **foreign-executor** rows: a
subtask/subagent projection written by another executor has no terminal envelope and no run-bound
debt, so monitoring loss is genuinely all we know about it. Managed `nebflow` rows never reach that
leg (its predicate requires `executor != nebflow`); their outcome comes from the evidence
reconciliation above.

## Test fixture contract upgrade

Two existing regression fixtures (`InternalRunAdmissionSpec`): the subagent envelope fixture and the
protected delegate envelope fixture. Both now carry `parentSessionId` and `taskRunId`, which the
production writers (`AgentFinishTurn` protected envelope, debt-derived envelope) already emit. The
fixtures were updated to match the tightened evidence contract; their semantics (a valid envelope
restores, an invalid one does not) are unchanged. "Do not edit the red test" forbids relaxing an
assertion to make a test pass — it does not forbid a fixture tracking the evidence contract it
exercises.
