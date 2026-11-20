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

## Test fixture contract upgrade

Two existing regression fixtures (`InternalRunAdmissionSpec`): the subagent envelope fixture and the
protected delegate envelope fixture. Both now carry `parentSessionId` and `taskRunId`, which the
production writers (`AgentFinishTurn` protected envelope, debt-derived envelope) already emit. The
fixtures were updated to match the tightened evidence contract; their semantics (a valid envelope
restores, an invalid one does not) are unchanged. "Do not edit the red test" forbids relaxing an
assertion to make a test pass — it does not forbid a fixture tracking the evidence contract it
exercises.
