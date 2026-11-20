# Subagent/SubTask terminal reconciliation

## Scope

A managed subagent run writes its final answer, then terminalizes its task row. When that terminal
write is lost (I/O failure, lost notification, process exit between answer and commit) the row stays
`running` while no actor owns it. The delegate completion gate waits on trusted descendants, so the
parent session is held indefinitely on a row nobody will ever finish.

This change makes terminalization robust and makes the remaining uncertainty visible instead of
inventing an outcome:

- the terminal row commit retries a bounded number of times on transient failure;
- startup recovery reconciles a row from authoritative completion evidence only — a persisted
  terminal envelope or a run-bound completion debt, each passing one shared identity check
  (runId binding, source, task and parent session);
- two conflicting authoritative evidences are never auto-resolved: the row is flagged for manual
  review;
- a row with no authoritative evidence is never terminalized by recovery; it is flagged
  (`suspectedLostTerminal`), surfaced on the task panel, alerted once, and can only be disposed of
  through an explicit manual force-terminalize entry point;
- the blocking `Subagent` primitive (the live kernel path) treats the parent-visible tool return as
  its delivery, matched per result rather than per message batch.

## Not in scope

- Retiring the `SubTask` tool. Its task rows and terminalization path are shared with the live
  blocking `Subagent` primitive, so the shared mechanism is repaired here regardless of that
  retirement.
- A durable not-delivered proof ledger. See `design.md` § "Not-delivered proof ledger (seam only)".

## Verified separately

Test evidence lives in `src/test/scala/nebflow/agent/InternalRunAdmissionSpec.scala` and
`src/test/scala/nebflow/agent/SubAgentTaskStatusSpec.scala`; red-then-green order for each assertion
is recorded in the branch commit history and in the terminal report accompanying this change.
