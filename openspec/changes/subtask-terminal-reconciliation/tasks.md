# Tasks

## Implemented and verified

- [x] Bounded retry on managed terminal row commit (`BackoffSupervisor` task update leg).
- [x] Shared `validTerminalEnvelope` identity check used by every envelope consumer (startup
      recovery, interrupted review, notification replay, protected supervisor delivery).
- [x] Run-bound completion debt for managed `subtask` and `subagent` rows: row-lock validated write,
      first evidence wins, lock order `row → debt` documented at both stores.
- [x] Startup reconciliation from envelope ∪ debt, with two-valid-and-identical → terminalize,
      conflict or invalid identity → manual review, neither → manual review only.
- [x] Reconciliation takes precedence over the legacy salvage chain and the manual
      `pending-recovery` transition for these sources; resource-free startup performs the
      evidence-only part.
- [x] Per-result provenance for the blocking `Subagent` tool return, and parent-transcript
      reconciliation of legacy rows from a matching qualified tool result.
- [x] `suspectedLostTerminal` field, panel presentation, one-shot alert, and manual
      `forceTerminalizeFlagged` entry point restricted to flagged rows.
- [x] Interrupted rows are never auto-repaired; empty or corrupt mailbox receipts are treated as
      unknown (fail-closed), pinned by tests.
- [x] Descendant hold is bounded: a stalled child row with no live actor is flagged for manual review
      instead of holding the delegate completion forever; a legitimately parked child keeps the hold.
      Pinned by `DelegateCompletionGateDescendantsSpec` H-e1/H-e2/H-e3.
- [x] End-to-end recovery of both lost-completion transports (`ManagedTerminalDeliveryE2ESpec`): a
      failed `Completed` send and an accepted-but-never-processed `Completed` are each terminalized
      and delivered from the run-bound debt at boot.
- [x] Startup ownership boundary: the interrupt leg stays scoped to foreign-executor rows; a managed
      `nebflow` row is never declared interrupted from monitoring loss.

## Not implemented

- [ ] Durable not-delivered proof ledger (append-only, watermark, fail-closed reads) and enabling
      the "provably undelivered → restore completed" branch. Seam and enablement conditions only —
      see `design.md`.
- [ ] Retiring the `SubTask` tool: separate change, out of scope here.
