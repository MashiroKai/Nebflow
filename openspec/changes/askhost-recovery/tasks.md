# Tasks

- [x] Retain the original AskHost request identity across an interrupted wait (ledger recovery).
- [x] Reconcile the durable mailbox entry and suppress replay after the mailbox consumed it.
- [x] Accept an exact duplicate reply once and refuse a conflicting decision for the same request.
- [ ] Combined end-to-end scenario (real actor + production startup scan + process restart) — NOT implemented as an integrated automated test.
- [ ] Reconcile legacy answered and otherwise orphaned requests; verify recovery waits and cancellation across the full lifecycle — NOT implemented.
- [ ] Close the watchdog single-diagnostic-slot gap for parallel calls — NOT implemented.
